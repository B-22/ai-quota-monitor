package com.tokenmon.mobile.data.model

import com.tokenmon.mobile.core.LENIENT_JSON
import com.tokenmon.mobile.ui.Format
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CodexResetCreditsTest {
    private val expiry = Instant.parse("2026-10-04T05:41:37.555Z")

    private fun stats(field: String = ""): HubStats = StatsParser.parseStats(
        """{"limits":{"providers":[{"provider":"codex","windows":[]$field}]}}"""
    )

    @Test
    fun `live sanitized Hub cards survive REST SSE and snapshot parsing`() {
        val raw = requireNotNull(javaClass.getResource("/fixtures/hub-codex-reset-credits-real.json"))
            .readText()
        val rest = StatsParser.parseStats(raw)
        val cards = rest.providers.single().resetCredits!!
        assertEquals(4L, cards.availableCount)
        assertEquals(expiry, cards.nextExpiresAt)
        assertEquals(4, cards.expirations.size)
        assertEquals("可用 4 张", Format.resetCreditsCount(cards))
        assertEquals(4, Format.resetCreditsExpirations(cards, expiry.minusSeconds(60)).size)

        val frame = buildJsonObject { put("stats", LENIENT_JSON.parseToJsonElement(raw)) }
        assertEquals(cards, StatsParser.parseStats(StatsParser.statsFromStreamFrame(frame)).providers.single().resetCredits)
        val snapshot = WidgetSnapshot(Instant.EPOCH, "", rest.providers)
        assertEquals(cards, WidgetSnapshot.decode(snapshot.encode())!!.providers.single().resetCredits)
    }

    @Test
    fun `explicit zero remains visible and missing cards are not zero`() {
        val zero = stats(""", "resetCredits":{"availableCount":0}""")
        assertEquals(0L, zero.providers.single().resetCredits!!.availableCount)
        assertEquals(1, zero.visibleProviders.size)
        assertEquals("可用 0 张", Format.resetCreditsCount(zero.providers.single().resetCredits!!))
        assertNull(stats().providers.single().resetCredits)
        assertNull(stats(""", "resetCredits":null""").providers.single().resetCredits)
    }

    @Test
    fun `expiry alone cannot imply a card count`() {
        val cards = stats(""", "resetCredits":{"availableCount":null,"nextExpiresAt":"$expiry"}""")
            .providers.single().resetCredits!!
        assertNull(cards.availableCount)
        assertEquals("数量未知", Format.resetCreditsCount(cards))
        assertEquals(1, Format.resetCreditsExpirations(cards, expiry.minusSeconds(60)).size)
    }

    @Test
    fun `bad reset fields leave quota windows readable`() {
        for (field in listOf("[]", "{}", "false", "\"invalid\"", """{"availableCount":"NaN","nextExpiresAt":"invalid","expirations":[null,{},false]}""")) {
            val provider = StatsParser.parseStats(
                """{"limits":{"providers":[{"provider":"codex","resetCredits":$field,"windows":[{"kind":"session","usedPercent":25}]}]}}"""
            ).providers.single()
            assertNull(provider.resetCredits)
            assertNull(provider.parseIssue)
            assertEquals(75.0, provider.windows.single().remainingPercent!!, 0.0)
        }
    }

    @Test
    fun `expiry list is sorted and invalid entries do not hide the earliest expiry`() {
        val later = expiry.plusSeconds(3600)
        val cards = stats(""", "resetCredits":{"availableCount":5,"nextExpiresAt":"$later","expirations":["$later","invalid","$expiry","$expiry"]}""")
            .providers.single().resetCredits!!
        assertEquals(listOf(expiry, later), cards.expirations)
        assertEquals(expiry, cards.nextExpiresAt)
        assertEquals(5L, cards.availableCount)
    }

    @Test
    fun `expired card dates do not subtract reported cards or say reset completed`() {
        val cards = stats(""", "resetCredits":{"availableCount":4,"nextExpiresAt":"$expiry"}""")
            .providers.single().resetCredits!!
        val line = Format.resetCreditsExpirations(cards, expiry).single()
        assertTrue(line.contains("已到期，等待更新"))
        assertFalse(line.contains("已重置"))
        assertEquals("可用 4 张", Format.resetCreditsCount(cards))
        assertTrue(Format.resetCreditsExpirations(cards, expiry.minusSeconds(3600)).single().contains("剩余 1小时"))
    }

    @Test
    fun `card count and expiration changes immediately change the feed fingerprint`() {
        val original = stats(""", "resetCredits":{"availableCount":4,"nextExpiresAt":"$expiry"}""")
        val countChanged = stats(""", "resetCredits":{"availableCount":3,"nextExpiresAt":"$expiry"}""")
        val expiryChanged = stats(""", "resetCredits":{"availableCount":4,"nextExpiresAt":"${expiry.plusSeconds(3600)}"}""")
        assertNotEquals(original.contentFingerprint(), countChanged.contentFingerprint())
        assertNotEquals(original.contentFingerprint(), expiryChanged.contentFingerprint())
        assertNotEquals(original.contentFingerprint(), stats().contentFingerprint())
    }
}
