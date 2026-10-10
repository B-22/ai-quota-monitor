package com.tokenmon.mobile.ui

import com.tokenmon.mobile.data.HubConfig
import org.junit.Assert.*
import org.junit.Test

class FeedLifecyclePolicyTest {
    private val configured = HubConfig("https://hub.example.invalid", hasSecret = true)

    @Test fun `feed requires foreground autoRefresh and configuration together`() {
        for (foreground in listOf(false, true)) {
            for (auto in listOf(false, true)) {
                for (hasSecret in listOf(false, true)) {
                    val plan = FeedLifecyclePolicy().reconcile(configured.copy(autoRefresh = auto, hasSecret = hasSecret), foreground, 0, false)
                    assertEquals(foreground && auto && hasSecret, plan.restart)
                }
            }
        }
        assertFalse(FeedLifecyclePolicy().reconcile(configured.copy(hubUrl = ""), true, 0, false).restart)
    }

    @Test fun `repeated foreground and unchanged settings do not restart an active feed`() {
        val policy = FeedLifecyclePolicy()
        assertTrue(policy.reconcile(configured, true, 0, false).restart)
        assertEquals(FeedPlan(false, false, false), policy.reconcile(configured, true, 0, true))
    }

    @Test fun `background and disabled autoRefresh stop then resume without clearing data`() {
        for (pauseByBackground in listOf(false, true)) {
            val policy = FeedLifecyclePolicy()
            policy.reconcile(configured, true, 0, false)
            val paused = if (pauseByBackground) configured else configured.copy(autoRefresh = false)
            val plan = policy.reconcile(paused, !pauseByBackground, 0, true)
            assertTrue(plan.stop)
            assertFalse(plan.restart)
            assertFalse(plan.clearData)
            assertTrue(policy.reconcile(configured, true, 0, false).restart)
        }
    }

    @Test fun `replacing a present secret restarts and clears its account data`() {
        val policy = FeedLifecyclePolicy()
        policy.reconcile(configured, true, 0, false)
        val replacement = policy.reconcile(configured, true, 1, true)
        assertTrue(replacement.restart)
        assertTrue(replacement.clearData)
    }

    @Test fun `Hub change in background invalidates data without opening a stream`() {
        val policy = FeedLifecyclePolicy()
        policy.reconcile(configured, false, 0, false)
        val changed = policy.reconcile(configured.copy(hubUrl = "https://other.example.invalid"), false, 0, false)
        assertTrue(changed.clearData)
        assertFalse(changed.restart)
    }

    @Test fun `clearing configuration stops and invalidates old data`() {
        val policy = FeedLifecyclePolicy()
        policy.reconcile(configured, true, 0, false)
        val plan = policy.reconcile(configured.copy(hasSecret = false), true, 1, true)
        assertTrue(plan.stop)
        assertTrue(plan.clearData)
        assertFalse(plan.restart)
    }

    @Test fun `completed feed can be started again with the same identity`() {
        val policy = FeedLifecyclePolicy()
        policy.reconcile(configured, true, 0, false)
        assertTrue(policy.reconcile(configured, true, 0, false).restart)
    }
}
