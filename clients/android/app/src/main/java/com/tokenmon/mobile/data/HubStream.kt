package com.tokenmon.mobile.data

import android.util.Log
import com.tokenmon.mobile.core.Json.obj
import com.tokenmon.mobile.core.Json.stringOrEmpty
import com.tokenmon.mobile.core.LENIENT_JSON
import com.tokenmon.mobile.data.model.HubStats
import com.tokenmon.mobile.data.model.StatsParser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.math.min
import kotlin.math.pow
import java.io.IOException

/** What the Hub pushed down `/api/stats/stream`. */
sealed interface StreamEvent {
    /** A full `snapshot` or `stats` frame — replace local state wholesale. */
    data class Full(val stats: HubStats, val reason: String) : StreamEvent

    /** A `freshness` frame — merge timestamps only, keep everything else. */
    data class Freshness(val stats: JsonObject, val reason: String) : StreamEvent

    data class Failed(
        val message: String,
        val httpCode: Int?,
        val retryable: Boolean,
        val useHttp1Fallback: Boolean = false
    ) : StreamEvent
}

/**
 * Client for `GET /api/stats/stream`.
 *
 * Per `src/hub/server.js` the Hub sends a complete `snapshot` immediately on
 * connect (so a reconnect always restores full state without depending on an
 * earlier event), `: hb` comment heartbeats every 30 seconds, and a complete
 * `stats` frame per coalesced ingest.
 *
 * This client sends `x-token-monitor-stream: 2`, the header official clients use.
 * With it, an ingest that only moved timestamps arrives as a small `freshness`
 * frame instead of the whole snapshot, and merging it follows the Hub's own
 * `applyFreshnessEvent()` semantics so limit providers and device detail survive.
 * The header is advisory: a Hub that ignores it simply sends `stats` frames,
 * which this client handles identically.
 */
class HubStream(private val secretProvider: () -> String) {

    fun events(base: HttpUrl, forceHttp1: Boolean = false): Flow<StreamEvent> = callbackFlow {
        val streamUrl = base.newBuilder().addPathSegments(HubEndpoints.STATS_STREAM).build()
        val secret = secretProvider().trim()

        val request = Request.Builder()
            .url(streamUrl)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("x-token-monitor-stream", "2")
            .apply { if (secret.isNotEmpty()) header("Authorization", "Bearer $secret") }
            .get()
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "SSE connected (HTTP ${response.code}, ${response.protocol})")
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                decodeEvent(type, data)?.let { trySend(it) }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(StreamEvent.Failed("Hub 关闭了连接", null, retryable = true))
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                trySend(classifyFailure(t, response?.code, response?.protocol))
            }
        }

        val http = if (forceHttp1) HubHttp.http1StreamClient else HubHttp.streamClient
        val source: EventSource = EventSources.createFactory(http)
            .newEventSource(request, listener)

        awaitClose {
            runCatching { source.cancel() }
            Log.i(TAG, "SSE cancelled")
        }
    }

    companion object {
        private const val TAG = "HubStream"

        /** Pure decoding seam: malformed payloads recover through poll/reconnect. */
        internal fun decodeEvent(type: String?, data: String): StreamEvent? {
            if (type != null && type !in setOf("snapshot", "stats", "freshness")) return null
            val frame = runCatching { obj(LENIENT_JSON.parseToJsonElement(data)) }.getOrNull()
                ?: return StreamEvent.Failed("实时数据不是 JSON 对象", null, retryable = true)
            if (type == "freshness") {
                val nested = obj(frame["stats"])
                    ?: return StreamEvent.Failed("实时增量缺少 stats 对象", null, retryable = true)
                return StreamEvent.Freshness(nested, frame.stringOrEmpty("reason"))
            }
            return runCatching {
                StreamEvent.Full(
                    StatsParser.parseStats(StatsParser.statsFromStreamFrame(frame)),
                    frame.stringOrEmpty("reason").ifEmpty { type ?: "snapshot" }
                )
            }.getOrElse {
                // Do not put payload fragments or account identifiers in errors.
                StreamEvent.Failed("无法解析实时数据", null, retryable = true)
            }
        }

        internal fun classifyFailure(t: Throwable?, code: Int?, protocol: Protocol?): StreamEvent.Failed {
            val authFailure = code == 401 || code == 403
            return StreamEvent.Failed(
                message = when {
                    authFailure -> "Secret 被 Hub 拒绝（HTTP $code）"
                    code != null && code !in 200..299 -> "实时连接失败（HTTP $code）"
                    else -> "实时连接中断，正在重试"
                },
                httpCode = code,
                retryable = !authFailure,
                // Require negotiated HTTP/2 plus the exact reset signal, rather
                // than guessing from a domain, a 60s interval or any IO failure.
                useHttp1Fallback = !authFailure && protocol == Protocol.HTTP_2 &&
                    t is IOException && t.message == "stream was reset: INTERNAL_ERROR"
            )
        }

        /**
         * Exponential backoff with jitter, capped at [MAX_BACKOFF_MS].
         *
         * The jitter matters more than the exponent here: several phones
         * reconnecting to the same Hub after it restarts would otherwise retry in
         * lockstep. Zero-delay retries are wrong too — a Hub that is down must
         * not be hit in a tight loop.
         */
        fun backoffDelayMs(attempt: Int, randomFraction: Float = Math.random().toFloat()): Long {
            val ceiling = min(
                MAX_BACKOFF_MS,
                (BASE_BACKOFF_MS * 2.0.pow(attempt.coerceAtLeast(0))).toLong()
            )
            val jitter = (BASE_JITTER_FRACTION + randomFraction.coerceIn(0f, 1f) * JITTER_SPAN)
            return (ceiling * jitter).toLong().coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
        }

        private const val BASE_BACKOFF_MS = 1_000L
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val BASE_JITTER_FRACTION = 0.5f
        private const val JITTER_SPAN = 0.5f
    }
}
