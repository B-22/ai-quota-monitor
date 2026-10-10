package com.tokenmon.mobile.ui

import com.tokenmon.mobile.data.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPresentationTest {
    @Test
    fun `cached data keeps transient reconnection in a neutral header only`() {
        val presentation = connectionPresentation(ConnectionStatus.Reconnecting, hasData = true)
        assertEquals("重连中", presentation.label)
        assertEquals(ChipTone.Neutral, presentation.tone)
        assertNull(presentation.banner)
        assertNull(connectionPresentation(ConnectionStatus.Connecting, hasData = true).banner)
    }

    @Test
    fun `initial connection can show a neutral loading message`() {
        for (status in listOf(ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)) {
            val presentation = connectionPresentation(status, hasData = false)
            assertEquals(ChipTone.Neutral, presentation.tone)
            assertNotNull(presentation.banner)
        }
    }

    @Test
    fun `offline and authentication failure stay explicit with or without cached data`() {
        for (status in listOf(ConnectionStatus.Offline, ConnectionStatus.AuthFailed)) {
            for (hasData in listOf(false, true)) {
                val presentation = connectionPresentation(status, hasData)
                assertEquals(ChipTone.Critical, presentation.tone)
                assertNotNull(presentation.banner)
                if (hasData) assertTrue(requireNotNull(presentation.banner).contains("上次数据"))
            }
        }
        assertEquals("认证失败", connectionPresentation(ConnectionStatus.AuthFailed, true).label)
    }

    @Test
    fun `normal transport modes do not interrupt the quota cards`() {
        for (status in listOf(ConnectionStatus.Live, ConnectionStatus.Polling)) {
            assertNull(connectionPresentation(status, hasData = true).banner)
        }
        assertEquals(ChipTone.Neutral, connectionPresentation(ConnectionStatus.Polling, true).tone)
    }

    @Test
    fun `paused automatic refresh overrides retained transport states without a banner`() {
        for (status in listOf(ConnectionStatus.NotConfigured, ConnectionStatus.Connecting, ConnectionStatus.Live,
            ConnectionStatus.Reconnecting, ConnectionStatus.Polling)) {
            for (hasData in listOf(false, true)) {
                val presentation = connectionPresentation(status, hasData, autoRefreshPaused = true)
                assertEquals("自动刷新已暂停", presentation.label)
                assertEquals(ChipTone.Neutral, presentation.tone)
                assertNull(presentation.banner)
            }
        }
    }

    @Test
    fun `paused automatic refresh never hides offline or authentication failures`() {
        for (status in listOf(ConnectionStatus.Offline, ConnectionStatus.AuthFailed)) {
            for (hasData in listOf(false, true)) {
                assertEquals(connectionPresentation(status, hasData), connectionPresentation(status, hasData, autoRefreshPaused = true))
            }
        }
    }

}
