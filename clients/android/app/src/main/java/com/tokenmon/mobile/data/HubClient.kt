package com.tokenmon.mobile.data

import com.tokenmon.mobile.core.Json.obj
import com.tokenmon.mobile.core.LENIENT_JSON
import com.tokenmon.mobile.data.model.HubHealth
import com.tokenmon.mobile.data.model.HubStats
import com.tokenmon.mobile.data.model.StatsParseException
import com.tokenmon.mobile.data.model.StatsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Endpoints on the official Hub, per `src/hub/server.js`. */
object HubEndpoints {
    const val HEALTH = "api/health"
    const val STATS = "api/stats"
    const val DEVICES = "api/devices"
    const val STATS_STREAM = "api/stats/stream"
}

/**
 * Outcome of "测试连接".
 *
 * Each failure mode the user needs to act on is its own case. "发生错误" is not
 * an acceptable result: "Secret 错误" and "Hub 没启动" lead to opposite actions.
 */
sealed interface ProbeResult {
    data object NotConfigured : ProbeResult
    data class InvalidUrl(val message: String) : ProbeResult

    /** Hub answered, secret accepted, `/api/stats` parsed. */
    data class Ok(
        val health: HubHealth,
        val stats: HubStats,
        val warning: String?
    ) : ProbeResult

    /** Hub answered 401/403 — the secret is wrong or missing. */
    data class Unauthorized(val httpCode: Int, val hubRuntime: String?) : ProbeResult

    /** Reached the Hub, but the body is not a Token Monitor payload. */
    data class Incompatible(val httpCode: Int, val message: String) : ProbeResult

    /** Hub answered with a status the app cannot interpret. */
    data class HttpError(val httpCode: Int, val endpoint: String, val body: String) : ProbeResult

    /** Never got an HTTP response: DNS, TCP, TLS, timeout. */
    data class Unreachable(val message: String, val hint: String?) : ProbeResult
}

/** Any response the caller should surface as a failed probe. */
class HubRequestException(
    val httpCode: Int,
    val endpoint: String,
    val body: String,
    val parseFailure: String? = null
) : Exception("HTTP $httpCode from $endpoint")

/**
 * Reads the official Token Monitor Hub directly.
 *
 * There is no intermediate service and no second source of truth: the Hub does
 * the aggregation and this class only reads it. Authentication uses
 * `Authorization: Bearer <secret>`, the header `src/shared/http.js` prefers. The
 * secret is never put in a query string — the Cloudflare Worker still accepts
 * `?secret=` for legacy iOS widgets, but a URL secret lands in proxy, platform
 * and history logs.
 */
class HubClient(private val secretProvider: () -> String) {

    /**
     * Secret used for the request currently being built.
     *
     * A probe has to be able to test a secret the user has typed but not yet
     * saved, so it overrides this for the duration of the call instead of writing
     * to the keystore first.
     */
    @Volatile
    private var secretOverride: String? = null

    /**
     * Normalizes typed input into a base URL.
     *
     * Accepts `https://host`, `https://host/hub/` and `http://100.64.1.2:17321`.
     * A bare host gets `https://` — never `http://`, since an unqualified address
     * is far more likely to be a real domain.
     */
    fun baseUrl(raw: String): HttpUrl? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull()
    }

    fun url(base: HttpUrl, endpoint: String): HttpUrl =
        base.newBuilder().addPathSegments(endpoint).build()

    /** `/api/health` — the one endpoint the Hub serves without a secret. */
    suspend fun health(base: HttpUrl): HubHealth = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(base, HubEndpoints.HEALTH))
            .get()
            .header("Accept", "application/json")
            .build()
        execute(request) { body, code ->
            try {
                StatsParser.parseHealth(body)
            } catch (error: StatsParseException) {
                throw HubRequestException(code, HubEndpoints.HEALTH, body, error.message)
            }
        }
    }

    suspend fun stats(base: HttpUrl): HubStats = withContext(Dispatchers.IO) {
        val request = authenticated(base, HubEndpoints.STATS).get().build()
        execute(request) { body, code ->
            try {
                StatsParser.parseStats(body)
            } catch (error: StatsParseException) {
                throw HubRequestException(code, HubEndpoints.STATS, body, error.message)
            }
        }
    }

    /** `/api/devices` — raw device records, used by the connection test. */
    suspend fun devices(base: HttpUrl): JsonObject = withContext(Dispatchers.IO) {
        val request = authenticated(base, HubEndpoints.DEVICES).get().build()
        execute(request) { body, code ->
            obj(LENIENT_JSON.parseToJsonElement(body))
                ?: throw HubRequestException(code, HubEndpoints.DEVICES, body, "设备列表不是 JSON 对象")
        }
    }

    /**
     * Runs the full connection test: `/api/health` for reachability and Hub type,
     * then `/api/stats` — which is secret-gated — to prove the secret works and
     * that the payload is one this build understands.
     */
    suspend fun probe(rawUrl: String, secret: String): ProbeResult {
        if (rawUrl.isBlank() || secret.isBlank()) return ProbeResult.NotConfigured
        val base = baseUrl(rawUrl) ?: return ProbeResult.InvalidUrl("无法识别为有效地址：$rawUrl")

        secretOverride = secret.trim()
        return try {
            probeAgainst(base)
        } finally {
            secretOverride = null
        }
    }

    private suspend fun probeAgainst(base: HttpUrl): ProbeResult {
        val health = try {
            health(base)
        } catch (error: HubRequestException) {
            return classify(error, HubEndpoints.HEALTH)
        } catch (error: IOException) {
            return ProbeResult.Unreachable(describeIo(error), ioHint(error, base))
        }

        val stats = try {
            stats(base)
        } catch (error: HubRequestException) {
            return classify(error, HubEndpoints.STATS, health)
        } catch (error: IOException) {
            return ProbeResult.Unreachable(describeIo(error), ioHint(error, base))
        }

        return ProbeResult.Ok(health = health, stats = stats, warning = healthWarning(health))
    }

    private suspend fun <T> execute(
        request: Request,
        transform: (String, Int) -> T
    ): T = withContext(Dispatchers.IO) {
        HubHttp.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HubRequestException(response.code, request.url.encodedPath.trim('/'), body)
            }
            transform(body, response.code)
        }
    }

    private fun authenticated(base: HttpUrl, endpoint: String): Request.Builder =
        Request.Builder()
            .url(url(base, endpoint))
            .header("Accept", "application/json")
            .apply {
                val secret = (secretOverride ?: secretProvider()).trim()
                if (secret.isNotEmpty()) header("Authorization", "Bearer $secret")
            }

    private fun classify(
        error: HubRequestException,
        endpoint: String,
        health: HubHealth? = null
    ): ProbeResult {
        val runtime = health?.runtime?.ifBlank { null }
        return when {
            error.httpCode == 401 || error.httpCode == 403 ->
                ProbeResult.Unauthorized(error.httpCode, runtime)

            // 404/405 on the data route means the route is not there. Pointed at
            // some other server, or at a proxy that rewrites /api, this is the
            // symptom — and "HTTP 404" alone does not tell the user what to do.
            error.httpCode == 404 || error.httpCode == 405 ->
                ProbeResult.Incompatible(
                    error.httpCode,
                    "该地址的 /${endpoint} 不存在。它可能不是 Token Monitor Hub，" +
                        "或者前面的反向代理把 /api 路径改写了。"
                )

            error.parseFailure != null ->
                ProbeResult.Incompatible(error.httpCode, error.parseFailure)
            else -> ProbeResult.HttpError(error.httpCode, endpoint, error.body.take(200))
        }
    }

    private fun healthWarning(health: HubHealth): String? = when {
        !health.ok -> "Hub /api/health 返回 ok=false，返回的数据可能不可信。"
        health.role.isNotEmpty() && health.role != "hub" ->
            "该地址返回 role=${health.role}，它可能不是 Token Monitor Hub。"
        !health.secretRequired -> "该 Hub 未设置 TOKEN_MONITOR_SECRET，数据以匿名方式返回。"
        health.hubBuild == null -> "该 Hub 未返回 hubBuild（较旧版本），功能上仍然兼容。"
        else -> null
    }

    private fun describeIo(error: IOException): String = when (error) {
        is UnknownHostException -> "无法解析主机名（DNS 失败）"
        is ConnectException -> "连接被拒绝（端口未开放，或 Hub 未启动）"
        is SocketTimeoutException -> "连接或读取超时"
        is SSLException -> "TLS 握手失败：${error.message?.takeIf { it.isNotBlank() } ?: "证书或协议不匹配"}"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    private fun ioHint(error: IOException, base: HttpUrl): String? = when {
        error is SSLException && base.scheme == "http" ->
            "该地址是 http://，但服务端似乎要求 https://。"
        error is UnknownHostException ->
            "确认地址正确，且手机能访问该 Hub 所在网络（局域网或 Tailscale）。"
        error is ConnectException ->
            "确认 Hub 监听的端口与这里填写的一致，且未被防火墙拦截。"
        else -> null
    }
}
