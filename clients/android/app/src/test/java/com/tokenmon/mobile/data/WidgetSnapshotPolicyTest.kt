package com.tokenmon.mobile.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSnapshotPolicyTest {
    private val config = HubConfig("https://hub.example.invalid", hasSecret = true)
    private val request = WidgetSnapshotRequest(config, 0)

    @Test fun `unchanged configuration and revision allow the matching snapshot`() {
        assertTrue(WidgetSnapshotPolicy.canPublish(request, config, 0, config.hubUrl))
    }

    @Test fun `a cleared secret or URL cannot publish old results`() {
        assertFalse(WidgetSnapshotPolicy.canPublish(request, config.copy(hasSecret = false), 1, config.hubUrl))
        assertFalse(WidgetSnapshotPolicy.canPublish(request, config.copy(hubUrl = ""), 1, config.hubUrl))
    }

    @Test fun `credential replacement with the same hasSecret rejects the old request`() {
        assertFalse(WidgetSnapshotPolicy.canPublish(request, config, 1, config.hubUrl))
    }

    @Test fun `changing Hub then returning to the same URL still rejects old results`() {
        assertFalse(WidgetSnapshotPolicy.canPublish(request, config, 2, config.hubUrl))
    }

    @Test fun `both current configuration and candidate snapshot URL are checked`() {
        val other = "https://other.example.invalid"
        assertFalse(WidgetSnapshotPolicy.canPublish(request, config.copy(hubUrl = other), 0, config.hubUrl))
        assertFalse(WidgetSnapshotPolicy.canPublish(request, config, 0, other))
        assertFalse(WidgetSnapshotPolicy.canPublish(request, config.copy(autoRefresh = false), 0, config.hubUrl))
    }

    @Test fun `persisted cache is retained only for a currently configured matching Hub`() {
        assertTrue(WidgetSnapshotPolicy.cacheMatches(config, config.hubUrl))
        assertFalse(WidgetSnapshotPolicy.cacheMatches(config, "https://other.example.invalid"))
        assertFalse(WidgetSnapshotPolicy.cacheMatches(config, ""))
        assertFalse(WidgetSnapshotPolicy.cacheMatches(config.copy(hasSecret = false), config.hubUrl))
    }

    @Test fun `a switch can proceed during fetch and its old result cannot refill cleared cache`() = runTest {
        val mutex = Mutex()
        var revision = 0L
        var current = config
        var cachedUrl: String? = config.hubUrl
        var redraws = 0
        val fetchStarted = CompletableDeferred<Unit>()
        val finishFetch = CompletableDeferred<Unit>()
        val worker = launch {
            val captured = mutex.withLock { WidgetSnapshotRequest(current, revision) }
            fetchStarted.complete(Unit)
            finishFetch.await()
            mutex.withLock {
                if (WidgetSnapshotPolicy.canPublish(captured, current, revision, captured.config.hubUrl)) {
                    cachedUrl = captured.config.hubUrl
                    redraws++
                }
            }
        }
        fetchStarted.await()
        // A deliberately pending fetch does not own the connection lock.
        mutex.withLock {
            revision++
            current = config.copy(hubUrl = "https://other.example.invalid")
            cachedUrl = null
        }
        finishFetch.complete(Unit)
        worker.join()
        assertNull(cachedUrl)
        assertEquals(0, redraws)
    }

    @Test fun `cache write and redraw finish before a queued switch clears them`() = runTest {
        val mutex = Mutex()
        var revision = 0L
        var cachedUrl: String? = null
        val calls = mutableListOf<String>()
        val writing = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val worker = launch {
            mutex.withLock {
                if (WidgetSnapshotPolicy.canPublish(request, config, revision, config.hubUrl)) {
                    cachedUrl = config.hubUrl
                    calls.add("write")
                    writing.complete(Unit)
                    finishWrite.await()
                    calls.add("redraw")
                }
            }
        }
        writing.await()
        val switching = launch {
            mutex.withLock {
                revision++
                calls.add("switch")
                cachedUrl = null
                calls.add("clear")
            }
        }
        runCurrent()
        assertEquals(0L, revision)
        assertEquals(listOf("write"), calls)
        finishWrite.complete(Unit)
        worker.join()
        switching.join()
        assertEquals(listOf("write", "redraw", "switch", "clear"), calls)
        assertNull(cachedUrl)
    }
}
