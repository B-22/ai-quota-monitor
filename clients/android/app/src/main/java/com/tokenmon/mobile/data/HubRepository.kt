package com.tokenmon.mobile.data

import com.tokenmon.mobile.NetworkMonitor
import com.tokenmon.mobile.data.model.HubStats
import com.tokenmon.mobile.data.model.StatsParser
import com.tokenmon.mobile.data.model.WidgetSnapshot
import com.tokenmon.mobile.widget.WidgetCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.time.Instant

/** How the dashboard is currently getting data. Drives the status chip. */
enum class ConnectionStatus {
    NotConfigured,
    Connecting,
    Live,
    Polling,
    Reconnecting,
    Offline,
    AuthFailed
}

/** One emission of the feed: a snapshot, a status change, or both. */
data class FeedUpdate(
    val stats: HubStats?,
    val status: ConnectionStatus,
    val message: String? = null,
    val receivedAt: Instant = Instant.now()
)

/**
 * Live feed over one Hub: SSE when it works, `/api/stats` when it does not.
 *
 * The Hub's own `/api/stats/stream` is the primary path. The poll is not a
 * replacement for it — it is a floor, so a proxy that silently buffers or drops
 * `text/event-stream` degrades the dashboard to a slightly staler view instead of
 * a frozen one.
 *
 * Both paths write through the same publish step, so the widget cache is written
 * once per good payload regardless of which one produced it.
 */
class HubRepository internal constructor(
    private val fetchStats: suspend (HttpUrl) -> HubStats,
    private val streamEvents: (HttpUrl, Boolean) -> Flow<StreamEvent>,
    private val writeSnapshot: suspend (WidgetSnapshot) -> Unit,
    private val onNetworkAvailable: (() -> Unit) -> (() -> Unit),
    private val onSnapshotWritten: suspend () -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val retryDelayMs: (Int) -> Long = { HubStream.backoffDelayMs(it) }
) {
    constructor(
        client: HubClient,
        stream: HubStream,
        widgetCache: WidgetCache,
        networkMonitor: NetworkMonitor,
        onSnapshotWritten: suspend () -> Unit = {}
    ) : this(
        fetchStats = client::stats,
        streamEvents = stream::events,
        writeSnapshot = widgetCache::write,
        onNetworkAvailable = networkMonitor::onAvailable,
        onSnapshotWritten = onSnapshotWritten
    )

    /** All shared state and emissions are serialized through the signal loop. */
    fun observe(base: HttpUrl, hubUrl: String): Flow<FeedUpdate> = channelFlow {
        val signals = Channel<Signal>(Channel.BUFFERED)
        val streamHealthy = MutableStateFlow(false)
        val policy = FeedConnectionPolicy()
        var latest: HubStats? = null
        var streamJob: Job? = null
        var generation = 0
        var forceHttp1 = false
        var lastFingerprint: String? = null
        var lastEmitAt: Long? = null
        var lastWidgetWriteAt: Long? = null
        var lastStatus: ConnectionStatus? = null
        var lastMessage: String? = null

        suspend fun emitStatus() {
            if (lastStatus != policy.status || lastMessage != policy.message) {
                lastStatus = policy.status
                lastMessage = policy.message
                send(FeedUpdate(null, policy.status, policy.message))
            }
        }

        suspend fun publish(stats: HubStats, fromStream: Boolean) {
            latest = stats
            val now = clockMs()
            policy.received(now, fromStream)
            streamHealthy.value = policy.streamHealthy
            val fingerprint = stats.contentFingerprint()
            val statusChanged = lastStatus != policy.status || lastMessage != policy.message
            if (fingerprint != lastFingerprint || lastEmitAt == null ||
                now - lastEmitAt!! >= MIN_EMIT_INTERVAL_MS || statusChanged
            ) {
                lastFingerprint = fingerprint
                lastEmitAt = now
                lastStatus = policy.status
                lastMessage = policy.message
                send(FeedUpdate(stats, policy.status, policy.message))
            }

            // Cache write and widget redraw share the same minute throttle.
            // Keep them in this scope so cancellation/join finishes old writes
            // before the ViewModel clears data for another Hub or credential.
            if (lastWidgetWriteAt == null || now - lastWidgetWriteAt!! >= WIDGET_WRITE_INTERVAL_MS) {
                lastWidgetWriteAt = now
                try {
                    writeSnapshot(
                        WidgetSnapshot(
                            capturedAt = stats.updatedAt ?: Instant.now(),
                            hubUrl = hubUrl,
                            providers = stats.providers
                        )
                    )
                    onSnapshotWritten()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A widget failure cannot turn a good Hub payload offline.
                }
            }
        }

        fun startStream() {
            val currentGeneration = ++generation
            val initiallyHttp1 = forceHttp1
            streamJob = launch {
                var attempt = 0
                var useHttp1 = initiallyHttp1
                while (currentCoroutineContext().isActive) {
                    signals.send(Signal.Started(currentGeneration))
                    val failure = try {
                        streamEvents(base, useHttp1).collect { event ->
                            if (event is StreamEvent.Failed) throw StreamStopped(event)
                            attempt = 0
                            signals.send(Signal.StreamData(currentGeneration, event))
                        }
                        StreamEvent.Failed("Hub 关闭了连接", null, retryable = true)
                    } catch (stopped: StreamStopped) {
                        stopped.event
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        StreamEvent.Failed("实时连接中断，正在重试", null, retryable = true)
                    }
                    signals.send(Signal.StreamFailed(currentGeneration, failure))
                    if (FeedConnectionPolicy.isAuthFailure(failure.httpCode)) return@launch
                    if (failure.useHttp1Fallback) useHttp1 = true
                    // retryable=false from a decoder is never authentication.
                    delay(retryDelayMs(attempt))
                    attempt++
                }
            }
        }

        val unregister = onNetworkAvailable { signals.trySend(Signal.NetworkAvailable) }
        startStream()
        val pollJob = launch {
            // A reset immediately switches to the active cadence instead of
            // waiting out a 120s idle sleep. Even a healthy stream gets a REST
            // liveness poll; a silently buffering stream cannot freeze the app.
            streamHealthy.collectLatest { healthy ->
                if (healthy) delay(POLL_IDLE_MS)
                while (currentCoroutineContext().isActive) {
                    try {
                        signals.send(Signal.PollData(fetchStats(base)))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        signals.send(Signal.PollFailed(error))
                    }
                    delay(if (healthy) POLL_IDLE_MS else POLL_ACTIVE_MS)
                }
            }
        }
        val tickJob = launch {
            while (currentCoroutineContext().isActive) {
                delay(1_000L)
                signals.send(Signal.Tick)
            }
        }

        try {
            for (signal in signals) {
                if (policy.authFailed) continue
                when (signal) {
                    is Signal.Started -> if (signal.generation == generation) {
                        policy.connecting(clockMs())
                        emitStatus()
                    }
                    is Signal.StreamData -> if (signal.generation == generation) {
                        when (val event = signal.event) {
                            is StreamEvent.Full -> publish(event.stats, fromStream = true)
                            is StreamEvent.Freshness -> latest?.let {
                                publish(StatsParser.applyFreshness(it, event.stats), fromStream = true)
                            }
                            is StreamEvent.Failed -> Unit
                        }
                    }
                    is Signal.StreamFailed -> if (signal.generation == generation) {
                        forceHttp1 = forceHttp1 || signal.event.useHttp1Fallback
                        policy.streamFailed(clockMs(), signal.event)
                        streamHealthy.value = false
                        emitStatus()
                    }
                    is Signal.PollData -> publish(signal.stats, fromStream = false)
                    is Signal.PollFailed -> {
                        policy.pollFailed(clockMs(), signal.error)
                        emitStatus()
                    }
                    Signal.NetworkAvailable -> if (!streamHealthy.value) {
                        // Connectivity callbacks run on a binder thread; they
                        // only enqueue signals, never replace jobs themselves.
                        streamJob?.cancel()
                        startStream()
                    }
                    Signal.Tick -> {
                        policy.tick(clockMs())
                        emitStatus()
                    }
                }
                if (policy.authFailed) {
                    streamJob?.cancel()
                    pollJob.cancel()
                    tickJob.cancel()
                }
            }
        } finally {
            unregister()
            signals.cancel()
            streamJob?.cancel()
            pollJob.cancel()
            tickJob.cancel()
        }
    }

    private sealed interface Signal {
        data class Started(val generation: Int) : Signal
        data class StreamData(val generation: Int, val event: StreamEvent) : Signal
        data class StreamFailed(val generation: Int, val event: StreamEvent.Failed) : Signal
        data class PollData(val stats: HubStats) : Signal
        data class PollFailed(val error: Exception) : Signal
        data object NetworkAvailable : Signal
        data object Tick : Signal
    }

    /** Control-flow signal, not an error: no stack trace, no message. */
    private class StreamStopped(val event: StreamEvent.Failed) :
        RuntimeException(null, null, false, false)

    companion object {
        /** SSE is down: poll often enough to stay useful. */
        const val POLL_ACTIVE_MS = 30_000L

        /** SSE is up: a slow liveness check that the stream is really alive. */
        const val POLL_IDLE_MS = 120_000L

        /**
         * Floor between two UI repaints when the quota figures have not moved.
         *
         * Quota changes on the order of minutes; the 2s cadence exists because
         * token counters move, and this dashboard is not a token dashboard.
         */
        const val MIN_EMIT_INTERVAL_MS = 10_000L

        /** How often the widget's cached snapshot is rewritten. */
        const val WIDGET_WRITE_INTERVAL_MS = 60_000L
    }
}
