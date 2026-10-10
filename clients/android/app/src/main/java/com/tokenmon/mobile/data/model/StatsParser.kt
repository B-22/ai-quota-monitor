package com.tokenmon.mobile.data.model

import com.tokenmon.mobile.core.Json
import com.tokenmon.mobile.core.Json.arrayOrNull
import com.tokenmon.mobile.core.Json.boolOrNull
import com.tokenmon.mobile.core.Json.doubleOrNull
import com.tokenmon.mobile.core.Json.intOrNull
import com.tokenmon.mobile.core.Json.longOrNull
import com.tokenmon.mobile.core.Json.number
import com.tokenmon.mobile.core.Json.objectListOrEmpty
import com.tokenmon.mobile.core.Json.obj
import com.tokenmon.mobile.core.Json.objOrNull
import com.tokenmon.mobile.core.Json.stringOrEmpty
import com.tokenmon.mobile.core.Json.stringOrNull
import com.tokenmon.mobile.core.LENIENT_JSON
import com.tokenmon.mobile.core.TimeFormat
import kotlinx.serialization.json.JsonObject

/**
 * Turns a Token Monitor Hub response into the phone's own model.
 *
 * The wire shape this targets was read out of the upstream source at commit
 * `d8a798c`, not from a sample payload:
 *
 *  - `src/hub/server.js`        → endpoints, auth gate, SSE framing
 *  - `src/shared/usage.js`      → `aggregateDevices()` / `getStats()` envelope
 *  - `src/shared/limits/core.js` → `normalizeLimitProvider()` / `normalizeLimitWindow()`
 *  - `src/shared/hubProtocol.js` → `freshness` event + its merge semantics
 *
 * Two rules drive every decision here:
 *
 *  1. **Unknown fields are free.** The parser walks a JSON tree and reads named
 *     keys, so a field the Hub adds tomorrow is simply not looked at. There is no
 *     generated schema to regenerate and no `MissingFieldException` to catch.
 *  2. **One bad row cannot break the page.** Each provider is parsed inside its
 *     own `runCatching`; a row that throws becomes a placeholder carrying
 *     [LimitProvider.parseIssue] so the rest of the dashboard still renders.
 */
object StatsParser {

    private val lenient = LENIENT_JSON

    /** Parses a `GET /api/stats` body. Throws only when the body is not JSON. */
    fun parseStats(body: String): HubStats = parseStats(requireJsonObject(body, "统计响应"))

    /**
     * Reads [body] as a JSON object or throws [StatsParseException].
     *
     * The lexer is inside the `runCatching` on purpose. A reverse proxy that
     * returns an HTML error page for `/api/stats` is the single most likely way to
     * hit this path, and real HTML contains whitespace and newlines between
     * tokens — which makes `parseToJsonElement` fail inside its lexer with
     * `JsonDecodingException` rather than reaching the "not an object" guard. Left
     * outside, a `SerializationException` would escape past the `StatsParseException`
     * catches in [com.tokenmon.mobile.data.HubClient] and surface as a raw crash
     * instead of the "数据格式不兼容" result the user needs to see.
     */
    private fun requireJsonObject(body: String, what: String): JsonObject =
        runCatching { lenient.parseToJsonElement(body) }
            .getOrElse { error ->
                throw StatsParseException("$what 不是合法 JSON（可能返回了 HTML 或被反向代理改写）")
            }
            .let { element ->
                obj(element) ?: throw StatsParseException("$what 不是 JSON 对象")
            }

    fun parseStats(root: JsonObject): HubStats {
        val limits = root.objOrNull("limits")
        val providers = limits?.objectListOrEmpty("providers").orEmpty().map { row ->
            runCatching { parseProvider(row) }.getOrElse { error -> unreadableProvider(row, error) }
        }
        return HubStats(
            updatedAt = TimeFormat.parseInstant(root.stringOrNull("updatedAt")),
            staleAfterMs = root.longOrNull("staleAfterMs") ?: 0L,
            limitsUpdatedAt = TimeFormat.parseInstant(limits?.stringOrNull("updatedAt")),
            limitsRefreshMs = limits?.longOrNull("refreshMs") ?: 0L,
            providers = providers,
            devices = root.objectListOrEmpty("devices").map { parseDevice(it) },
            subscriptionsUpdatedAt = root.stringOrEmpty("subscriptionsUpdatedAt")
        )
    }

    /**
     * Parses a `GET /api/health` body.
     *
     * `hubBuild` is optional by contract: the API docs state that a Hub predating
     * the marker returns no `hubBuild` and "remains otherwise compatible". The
     * difference between *absent* (legacy) and *present but malformed* (a Hub we
     * do not recognize) matters, so the two are not collapsed into one null.
     */
    fun parseHealth(body: String): HubHealth = parseHealth(requireJsonObject(body, "健康检查响应"))

    fun parseHealth(root: JsonObject): HubHealth {
        val rawBuild = root["hubBuild"]
        return HubHealth(
            ok = root.boolOrNull("ok") ?: false,
            role = root.stringOrEmpty("role"),
            runtime = root.stringOrEmpty("runtime"),
            version = root.intOrNull("version"),
            deviceCount = root.intOrNull("deviceCount") ?: 0,
            secretRequired = root.boolOrNull("secretRequired") ?: false,
            hubBuild = when (rawBuild) {
                null -> null
                else -> runCatching { parseHubBuild(obj(rawBuild) ?: error("not an object")) }.getOrNull()
            }
        )
    }

    private fun parseHubBuild(node: JsonObject): HubBuild = HubBuild(
        schemaVersion = node.intOrNull("schemaVersion"),
        runtime = node.stringOrEmpty("runtime"),
        coreBuildId = node.stringOrEmpty("coreBuildId"),
        runtimeBuildId = node.stringOrEmpty("runtimeBuildId")
    )

    private fun parseDevice(node: JsonObject) = DeviceInfo(
        deviceId = node.stringOrEmpty("deviceId"),
        hostname = node.stringOrEmpty("hostname"),
        platform = node.stringOrEmpty("platform"),
        osName = node.stringOrEmpty("osName"),
        stale = node.boolOrNull("stale") ?: false,
        updatedAt = TimeFormat.parseInstant(node.stringOrNull("updatedAt"))
    )

    private fun parseProvider(node: JsonObject): LimitProvider {
        val providerId = node.stringOrEmpty("provider").trim().lowercase()
        if (providerId.isEmpty()) throw StatsParseException("provider 字段缺失或为空")

        val windows = node.arrayOrNull("windows").orEmpty().mapNotNull { element ->
            val windowNode = obj(element) ?: return@mapNotNull null
            runCatching { parseWindow(providerId, windowNode) }.getOrNull()
        }

        val balance = node.objOrNull("balance")

        return LimitProvider(
            providerId = providerId,
            displayName = ProviderCatalog.displayName(providerId),
            accountKey = node.stringOrEmpty("accountKey"),
            accountName = node.stringOrEmpty("accountName"),
            accountEmail = node.stringOrEmpty("accountEmail"),
            accountLabel = node.stringOrEmpty("accountLabel"),
            planLabel = node.stringOrEmpty("planLabel"),
            workspaceKind = node.stringOrEmpty("workspaceKind"),
            status = node.stringOrEmpty("status"),
            source = node.stringOrEmpty("source"),
            updatedAt = TimeFormat.parseInstant(node.stringOrNull("updatedAt")),
            windows = windows,
            balanceAmount = balance?.let { Json.number(it["amount"]) }
                ?: node.doubleOrNull("balanceUsd"),
            // Upstream upper-cases on ingest; normalise again so a hand-written
            // or future payload's "cny" reaches the UI the same way `windows[].currency` does.
            balanceCurrency = balance?.stringOrNull("currency")?.trim()?.uppercase()?.ifBlank { null },
            // `stale` is added by the Hub's cross-device aggregation
            // (`aggregateLimits`); it is absent on a per-device record.
            stale = node.boolOrNull("stale") ?: false,
            sourceDeviceId = node.stringOrEmpty("sourceDeviceId"),
            actionRequired = node.stringOrNull("actionRequired")?.ifBlank { null },
            adapterId = node.stringOrNull("adapterId")?.ifBlank { null },
            resetCredits = if (providerId == "codex") parseResetCredits(node.objOrNull("resetCredits")) else null
        )
    }

    /** Reads the Hub's normalized fields; missing cards are not zero cards. */
    private fun parseResetCredits(node: JsonObject?): ResetCredits? {
        if (node == null) return null
        val count = node.doubleOrNull("availableCount")?.takeIf { it.isFinite() }
            ?.let { kotlin.math.floor(it).coerceAtLeast(0.0).toLong() }
        val expirations = node.arrayOrNull("expirations").orEmpty()
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .mapNotNull { TimeFormat.parseInstant(it) }.distinct().sorted()
        val nextExpiry = listOfNotNull(
            TimeFormat.parseInstant(node.stringOrNull("nextExpiresAt")),
            expirations.firstOrNull()
        ).minOrNull()
        if (count == null && nextExpiry == null) return null
        return ResetCredits(count, nextExpiry, expirations)
    }

    /**
     * Placeholder for a provider row that could not be read.
     *
     * The id is recovered if at all possible so the user can tell *which*
     * provider the Hub's new shape broke.
     */
    private fun unreadableProvider(node: JsonObject, error: Throwable): LimitProvider {
        val providerId = node.stringOrEmpty("provider").trim().lowercase().ifEmpty { "unknown" }
        return LimitProvider(
            providerId = providerId,
            displayName = ProviderCatalog.displayName(providerId),
            accountKey = "",
            accountName = "",
            accountEmail = "",
            accountLabel = "",
            planLabel = "",
            workspaceKind = "",
            status = "",
            source = "",
            updatedAt = null,
            windows = emptyList(),
            balanceAmount = null,
            balanceCurrency = null,
            stale = true,
            sourceDeviceId = "",
            actionRequired = null,
            adapterId = null,
            parseIssue = error.message ?: "无法解析该 Provider 数据"
        )
    }

    /**
     * Mirrors `normalizeLimitWindow()`.
     *
     * Percentage resolution follows the Hub exactly: an explicit `usedPercent`
     * wins and is clamped to 0..100; otherwise it is derived from `used / limit`
     * only when `limit > 0`; otherwise there is no percentage at all and the
     * window renders as an absolute amount.
     */
    private fun parseWindow(providerId: String, node: JsonObject): LimitWindow {
        val rawKind = node.stringOrEmpty("kind")
        val kind = WindowKind.from(rawKind)
        if (kind == WindowKind.UNKNOWN) throw StatsParseException("未知的窗口类型：${rawKind.ifEmpty { "(缺失)" }}")

        val used = node.doubleOrNull("used")
        val limit = node.doubleOrNull("limit")
        val explicitPercent = node.doubleOrNull("usedPercent")
        val usedPercent = Json.percent(explicitPercent ?: derivedPercent(used, limit))

        return LimitWindow(
            kind = kind,
            rawKind = rawKind,
            label = ProviderCatalog.windowLabel(providerId, node.stringOrEmpty("label"), kind),
            metric = node.stringOrNull("metric")?.trim()?.lowercase()?.ifBlank { null },
            usedPercent = usedPercent,
            // The Hub computes remainingPercent as 100 - usedPercent, rounded to 3dp.
            remainingPercent = usedPercent?.let { Math.round((100.0 - it) * 1000.0) / 1000.0 },
            used = used,
            limit = limit,
            remaining = node.doubleOrNull("remaining"),
            currency = node.stringOrNull("currency")?.trim()?.uppercase()?.ifBlank { null },
            resetsAtRaw = node.stringOrEmpty("resetsAt"),
            resetsAt = TimeFormat.parseInstant(node.stringOrNull("resetsAt")),
            resetDescription = node.stringOrEmpty("resetDescription"),
            showMeter = node.boolOrNull("showMeter") ?: true,
            boundaryKind = node.stringOrNull("boundaryKind")?.trim()?.lowercase()?.ifBlank { null },
            additional = node.boolOrNull("additional") ?: false,
            limitId = node.stringOrNull("limitId")?.ifBlank { null },
            detail = node.stringOrEmpty("detail"),
            windowMinutes = node.longOrNull("windowMinutes")
        )
    }

    private fun derivedPercent(used: Double?, limit: Double?): Double? {
        if (used == null || limit == null || limit <= 0.0) return null
        return (used / limit) * 100.0
    }

    /**
     * Unwraps the `stats` object out of one `/api/stats/stream` SSE frame.
     *
     * The frame envelope is `{ type, reason, stats, at }` — `limits` lives at
     * `frame.stats.limits`, **one level deeper** than in a `GET /api/stats` body,
     * where it sits at the root. Feeding a whole frame to [parseStats] therefore
     * silently yields zero providers instead of an error, which shows up as the
     * dashboard alternating between data and "no quota data" as the SSE and
     * polling paths take turns publishing.
     *
     * Kept as a separate pure function so the unwrapping is unit-testable
     * without a live stream.
     */
    fun statsFromStreamFrame(frame: JsonObject): JsonObject =
        obj(frame["stats"])
            ?: throw StatsParseException("SSE 帧缺少 stats 对象（顶层只有 ${frame.keys.joinToString()}）")

    /**
     * Merges a `freshness` SSE frame into an existing snapshot.
     *
     * Port of the Hub's own `applyFreshnessEvent()` from
     * `src/shared/hubProtocol.js`, kept structurally identical on purpose: when
     * the client sends `x-token-monitor-stream: 2`, a timestamp-only ingest sends
     * only freshness metadata, and this is the documented way to fold it in
     * without replacing the limit providers, periods or session detail.
     */
    fun applyFreshness(current: HubStats, freshnessStats: JsonObject?): HubStats {
        if (freshnessStats == null) return current
        val byDeviceId = freshnessStats.arrayOrNull("devices").orEmpty()
            .mapNotNull { obj(it) }
            .associateBy({ it.stringOrEmpty("deviceId") }, { it })

        return current.copy(
            updatedAt = TimeFormat.parseInstant(freshnessStats.stringOrNull("updatedAt")) ?: current.updatedAt,
            staleAfterMs = freshnessStats.longOrNull("staleAfterMs") ?: current.staleAfterMs,
            limitsUpdatedAt = TimeFormat.parseInstant(
                freshnessStats.objOrNull("limits")?.stringOrNull("updatedAt")
            ) ?: current.limitsUpdatedAt,
            devices = current.devices.map { device ->
                val patch = byDeviceId[device.deviceId] ?: return@map device
                device.copy(
                    updatedAt = TimeFormat.parseInstant(patch.stringOrNull("updatedAt")) ?: device.updatedAt,
                    stale = patch.boolOrNull("stale") ?: device.stale
                )
            }
        )
    }
}

/** Thrown when a body cannot be read as a Token Monitor Hub response at all. */
class StatsParseException(message: String) : Exception(message)
