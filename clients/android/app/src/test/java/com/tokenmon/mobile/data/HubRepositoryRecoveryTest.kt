package com.tokenmon.mobile.data

import com.tokenmon.mobile.data.model.HubStats
import com.tokenmon.mobile.data.model.StatsParser
import com.tokenmon.mobile.data.model.WidgetSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HubRepositoryRecoveryTest {
    private class Harness(val scope: TestScope) {
        val stats: HubStats = StatsParser.parseStats(
            checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/hub-stream-frame-real.json"))
                .bufferedReader().use { com.tokenmon.mobile.core.LENIENT_JSON.parseToJsonElement(it.readText()) }
                .let { StatsParser.statsFromStreamFrame(it as kotlinx.serialization.json.JsonObject) }
        )
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val updates = mutableListOf<FeedUpdate>()
        val snapshots = mutableListOf<WidgetSnapshot>()
        val protocols = mutableListOf<Boolean>()
        var redraws = 0
        var polls = 0
        var unregistered = false
        var available: () -> Unit = {}
        var fetch: suspend () -> HubStats = { stats }
        var redraw: suspend () -> Unit = { redraws++ }
        val repository = HubRepository(
            fetchStats = { polls++; fetch() },
            streamEvents = { _, http1 -> protocols.add(http1); events.receiveAsFlow() },
            writeSnapshot = { snapshots.add(it) },
            onNetworkAvailable = { callback -> available = callback; { unregistered = true } },
            onSnapshotWritten = { redraw() },
            clockMs = { scope.testScheduler.currentTime },
            retryDelayMs = { 1_000L }
        )
        fun start() = scope.backgroundScope.launch {
            repository.observe("https://hub.example.invalid".toHttpUrl(), "https://hub.example.invalid")
                .collect { updates.add(it) }
        }
        fun full() { check(events.trySend(StreamEvent.Full(stats, "snapshot")).isSuccess) }
        fun fail(code: Int? = null, retryable: Boolean = true, http1: Boolean = false) {
            check(events.trySend(StreamEvent.Failed("连接中断", code, retryable, http1)).isSuccess)
        }
    }

    @Test fun `fallback poll remains visible while SSE retries`() = runTest {
        val h = Harness(this)
        h.full()
        h.start()
        runCurrent()
        advanceTimeBy(1_000)
        h.fail()
        runCurrent()
        assertEquals(ConnectionStatus.Polling, h.updates.last().status)
        val afterPoll = h.updates.size
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(h.updates.drop(afterPoll).none { it.status == ConnectionStatus.Reconnecting })
        assertNull(h.updates.last().message)
    }

    @Test fun `reset stays quiet briefly and a prolonged outage remains visible`() = runTest {
        val h = Harness(this)
        h.fetch = { throw IOException() }
        h.full()
        h.start()
        runCurrent()
        advanceTimeBy(1_000)
        h.fail()
        runCurrent()
        assertNull(h.updates.last().message)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(ConnectionStatus.Offline, h.updates.last().status)
        assertNotNull(h.updates.last().message)
        assertTrue(h.updates.any { it.stats == h.stats })
    }

    @Test fun `recovery with unchanged payload bypasses content throttling`() = runTest {
        val h = Harness(this)
        h.full()
        h.start()
        runCurrent()
        h.fail()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        h.full()
        runCurrent()
        assertEquals(ConnectionStatus.Live, h.updates.last().status)
        assertEquals(h.stats, h.updates.last().stats)
        assertNull(h.updates.last().message)
    }

    @Test fun `decoder failure does not reject authentication and can recover`() = runTest {
        val h = Harness(this)
        h.fetch = { throw HubRequestException(200, HubEndpoints.STATS, "", "bad JSON") }
        h.start()
        runCurrent()
        h.fail(retryable = false)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        h.full()
        runCurrent()
        assertTrue(h.updates.none { it.status == ConnectionStatus.AuthFailed })
        assertEquals(ConnectionStatus.Live, h.updates.last().status)
    }

    @Test fun `authentication rejection stops both transports and ignores network callbacks`() = runTest {
        val h = Harness(this)
        h.start()
        runCurrent()
        h.fail(code = 401, retryable = false)
        runCurrent()
        val polls = h.polls
        val connections = h.protocols.size
        h.available()
        advanceTimeBy(120_001)
        runCurrent()
        assertEquals(ConnectionStatus.AuthFailed, h.updates.last().status)
        assertEquals(polls, h.polls)
        assertEquals(connections, h.protocols.size)
    }

    @Test fun `HTTP1 fallback applies only after explicit reset and survives network restart`() = runTest {
        val h = Harness(this)
        h.start()
        runCurrent()
        assertEquals(listOf(false), h.protocols)
        h.fail(http1 = true)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(true, h.protocols.last())
        h.available()
        runCurrent()
        assertEquals(true, h.protocols.last())
    }

    @Test fun `two second frames redraw the widget only after minute throttled cache writes`() = runTest {
        val h = Harness(this)
        h.full()
        h.start()
        runCurrent()
        repeat(29) {
            advanceTimeBy(2_000)
            h.full()
            runCurrent()
        }
        assertEquals(1, h.snapshots.size)
        assertEquals(1, h.redraws)
        advanceTimeBy(2_000)
        h.full()
        runCurrent()
        assertEquals(2, h.snapshots.size)
        assertEquals(2, h.redraws)
    }

    @Test fun `widget redraw failure cannot break a healthy feed`() = runTest {
        val h = Harness(this)
        h.redraw = { throw IOException() }
        h.full()
        h.start()
        runCurrent()
        advanceTimeBy(10_001)
        h.full()
        runCurrent()
        assertEquals(ConnectionStatus.Live, h.updates.last().status)
        assertNull(h.updates.last().message)
    }

    @Test fun `cancelled old Hub poll cannot repopulate cache and unregisters callback`() = runTest {
        val h = Harness(this)
        val pending = CompletableDeferred<HubStats>()
        h.fetch = { pending.await() }
        val job = h.start()
        runCurrent()
        job.cancelAndJoin()
        pending.complete(h.stats)
        h.full()
        runCurrent()
        assertTrue(h.unregistered)
        assertTrue(h.snapshots.isEmpty())
        assertEquals(0, h.redraws)
    }
}
