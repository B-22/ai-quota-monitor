package com.tokenmon.mobile.data

import com.tokenmon.mobile.HubFixtures
import okhttp3.Protocol
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HubStreamRecoveryTest {
    private fun fixture(): String = checkNotNull(javaClass.classLoader)
        .getResourceAsStream("fixtures/hub-stream-frame-real.json")
        ?.bufferedReader()?.use { it.readText() } ?: error("Missing sanitized SSE fixture")

    @Test fun `sanitized snapshot is decoded through the shared parser`() {
        val event = HubStream.decodeEvent("snapshot", fixture()) as StreamEvent.Full
        assertTrue(event.stats.visibleProviders.isNotEmpty())
    }

    @Test fun `malformed full frames are retryable data errors without authentication codes`() {
        for (body in listOf("<html>error</html>", "[]", "{}", "{\"stats\":null}")) {
            val event = HubStream.decodeEvent("stats", body) as StreamEvent.Failed
            assertTrue(event.retryable)
            assertNull(event.httpCode)
            assertFalse(event.useHttp1Fallback)
        }
    }

    @Test fun `freshness remains an incremental event and malformed freshness recovers`() {
        val event = HubStream.decodeEvent("freshness", "{\"stats\":${HubFixtures.FRESHNESS_STATS_BODY}}")
        assertTrue(event is StreamEvent.Freshness)
        for (body in listOf("invalid", "{}", "{\"stats\":[]}")) {
            val failed = HubStream.decodeEvent("freshness", body) as StreamEvent.Failed
            assertTrue(failed.retryable)
            assertNull(failed.httpCode)
        }
    }

    @Test fun `unknown event types are ignored before parsing`() {
        assertNull(HubStream.decodeEvent("future-event", "not JSON"))
    }

    @Test fun `only 401 and 403 reject authentication`() {
        for (code in listOf(null, 200, 400, 404, 429, 500, 502)) {
            assertTrue(HubStream.classifyFailure(IOException(), code, Protocol.HTTP_2).retryable)
        }
        for (code in listOf(401, 403)) {
            val failure = HubStream.classifyFailure(IOException(), code, Protocol.HTTP_2)
            assertFalse(failure.retryable)
            assertFalse(failure.useHttp1Fallback)
        }
    }

    @Test fun `HTTP1 compatibility requires observed HTTP2 INTERNAL_ERROR`() {
        val reset = IOException("stream was reset: INTERNAL_ERROR")
        assertTrue(HubStream.classifyFailure(reset, 200, Protocol.HTTP_2).useHttp1Fallback)
        assertFalse(HubStream.classifyFailure(reset, 200, Protocol.HTTP_1_1).useHttp1Fallback)
        assertFalse(HubStream.classifyFailure(reset, null, null).useHttp1Fallback)
        assertFalse(HubStream.classifyFailure(IOException("timeout"), 200, Protocol.HTTP_2).useHttp1Fallback)
        assertFalse(HubStream.classifyFailure(IOException("stream was reset: CANCEL"), 200, Protocol.HTTP_2).useHttp1Fallback)
    }

    @Test fun `compatibility leaves REST protocols and stream deadlines unchanged`() {
        assertTrue(HubHttp.client.protocols.contains(Protocol.HTTP_2))
        assertTrue(HubHttp.streamClient.protocols.contains(Protocol.HTTP_2))
        assertEquals(listOf(Protocol.HTTP_1_1), HubHttp.http1StreamClient.protocols)
        assertEquals(0, HubHttp.http1StreamClient.readTimeoutMillis)
        assertTrue(HubHttp.client.readTimeoutMillis > 0)
        assertSame(HubHttp.client.sslSocketFactory, HubHttp.http1StreamClient.sslSocketFactory)
        assertSame(HubHttp.client.hostnameVerifier, HubHttp.http1StreamClient.hostnameVerifier)
    }
}
