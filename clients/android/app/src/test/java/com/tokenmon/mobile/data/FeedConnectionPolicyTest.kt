package com.tokenmon.mobile.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FeedConnectionPolicyTest {
    private val reset = StreamEvent.Failed("实时连接中断", null, retryable = true)

    @Test fun `brief reset after data has no error banner`() {
        val policy = FeedConnectionPolicy()
        policy.received(0, fromStream = true)
        policy.streamFailed(1_000, reset)
        assertEquals(ConnectionStatus.Reconnecting, policy.status)
        assertNull(policy.message)
        policy.tick(59_999)
        assertNull(policy.message)
        policy.tick(60_000)
        assertEquals(ConnectionStatus.Offline, policy.status)
        assertNotNull(policy.message)
    }

    @Test fun `successful polls win over reconnect attempts and renew grace`() {
        val policy = FeedConnectionPolicy()
        policy.received(0, fromStream = true)
        policy.streamFailed(1_000, reset)
        policy.received(2_000, fromStream = false)
        policy.connecting(3_000)
        policy.streamFailed(4_000, reset)
        policy.pollFailed(5_000, IOException())
        policy.tick(61_999)
        assertEquals(ConnectionStatus.Polling, policy.status)
        assertNull(policy.message)
        policy.tick(62_000)
        assertEquals(ConnectionStatus.Offline, policy.status)
    }

    @Test fun `initial poll failure remains visible while retrying`() {
        val policy = FeedConnectionPolicy()
        policy.pollFailed(0, IOException())
        policy.connecting(1_000)
        assertEquals(ConnectionStatus.Offline, policy.status)
        assertNotNull(policy.message)
    }

    @Test fun `bad poll cannot hide a recently delivering stream`() {
        val policy = FeedConnectionPolicy()
        policy.received(0, fromStream = true)
        policy.pollFailed(1_000, IOException())
        assertEquals(ConnectionStatus.Live, policy.status)
        assertNull(policy.message)
        policy.tick(60_000)
        assertEquals(ConnectionStatus.Offline, policy.status)
        policy.received(60_001, fromStream = true)
        assertEquals(ConnectionStatus.Live, policy.status)
        assertNull(policy.message)
    }

    @Test fun `retryability never identifies authentication`() {
        val policy = FeedConnectionPolicy()
        policy.streamFailed(0, StreamEvent.Failed("无法解析", null, retryable = false))
        assertNotEquals(ConnectionStatus.AuthFailed, policy.status)
        policy.pollFailed(0, HubRequestException(200, HubEndpoints.STATS, "", "invalid JSON"))
        assertEquals(ConnectionStatus.Offline, policy.status)
        assertTrue(policy.message!!.contains("解析"))
    }

    @Test fun `HTTP rejection stays terminal even after queued success or retry`() {
        for (code in listOf(401, 403)) {
            val policy = FeedConnectionPolicy()
            policy.received(0, fromStream = true)
            policy.pollFailed(1_000, HubRequestException(code, HubEndpoints.STATS, ""))
            policy.received(2_000, fromStream = true)
            policy.connecting(3_000)
            assertEquals(ConnectionStatus.AuthFailed, policy.status)
            assertFalse(policy.streamHealthy)
        }
    }
}
