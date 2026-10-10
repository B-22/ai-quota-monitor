package com.tokenmon.mobile.data.model

import com.tokenmon.mobile.HubFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The Home Screen widget is killed between updates, so it cannot fetch: it renders
 * a snapshot the app wrote on its last successful fetch.
 *
 * The snapshot is stored **in the Hub wire shape** and decoded with the same
 * [StatsParser], so this test is really a statement that encode → decode is a
 * fixed point for every field the widget and dashboard read. A field added to
 * [LimitWindow] and forgotten in `encodeWindow` is exactly the class of bug this
 * catches.
 */
class WidgetSnapshotTest {

    private val capturedAt: Instant = Instant.parse("2026-05-18T04:00:00Z")

    private fun parsedSnapshot() = WidgetSnapshot(
        capturedAt = capturedAt,
        hubUrl = "https://hub.example.com",
        providers = StatsParser.parseStats(HubFixtures.STATS_BODY).providers
    )

    @Test
    fun `providers and windows survive an encode decode round trip`() {
        val original = parsedSnapshot()
        val restored = WidgetSnapshot.decode(original.encode())!!

        assertEquals(original.providers.size, restored.providers.size)
        assertEquals(
            original.providers.map { it.providerId },
            restored.providers.map { it.providerId }
        )
        // Equality of the whole list is the strong assertion; the field-by-field
        // checks below name the properties a silent drop would hide.
        assertEquals(original.providers, restored.providers)
    }

    @Test
    fun `provider identity and balance fields round trip`() {
        val restored = WidgetSnapshot.decode(parsedSnapshot().encode())!!

        val claude = restored.providers.first { it.providerId == "claude" }
        assertEquals("Claude", claude.displayName)
        assertEquals("sha256:claude-account-0001", claude.accountKey)
        assertEquals("Ada Lovelace", claude.accountName)
        assertEquals("ada@example.com", claude.accountEmail)
        assertEquals("Ada", claude.accountLabel)
        assertEquals("Max", claude.planLabel)
        assertEquals("personal", claude.workspaceKind)
        assertEquals("ok", claude.status)
        assertEquals("oauth", claude.source)
        assertEquals("macbook", claude.sourceDeviceId)
        assertEquals(Instant.parse("2026-05-18T03:59:00Z"), claude.updatedAt)
        assertFalse(claude.stale)
        assertNull(claude.parseIssue)

        val deepseek = restored.providers.first { it.providerId == "deepseek" }
        assertTrue(deepseek.stale)
        assertEquals(18.25, deepseek.balanceAmount!!, 1e-9)
        assertEquals("CNY", deepseek.balanceCurrency)

        val thirdparty = restored.providers.first { it.providerId == "thirdparty" }
        assertEquals("newapi-account", thirdparty.adapterId)
        assertEquals(7.5, thirdparty.balanceAmount!!, 1e-9)
    }

    @Test
    fun `window percentages resets and money fields round trip`() {
        val restored = WidgetSnapshot.decode(parsedSnapshot().encode())!!
        val claude = restored.providers.first { it.providerId == "claude" }

        val session = claude.windows.first { it.kind == WindowKind.SESSION }
        assertEquals("session", session.rawKind)
        assertEquals(WindowKind.SESSION, session.kind)
        assertEquals("会话", session.label)
        assertEquals(42.0, session.usedPercent!!, 0.0)
        assertEquals(58.0, session.remainingPercent!!, 0.0)
        assertEquals(Instant.parse("2026-05-18T05:00:00Z"), session.resetsAt)
        assertEquals("2026-05-18T05:00:00.000Z", session.resetsAtRaw)
        assertTrue(session.hasMeter)
        assertFalse(session.additional)
        assertFalse(session.isMoneyWindow)

        val credits = claude.windows.single { it.metric == "credits" }
        assertEquals("USD", credits.currency)
        assertEquals(42.5, credits.remaining!!, 1e-9)
        assertTrue(credits.isMoneyWindow)
        assertFalse(credits.hasMeter)
        assertEquals("mixed", credits.boundaryKind)
        assertEquals("重置 / 到期", credits.boundaryWording)
    }

    @Test
    fun `additional limit ids and window minutes round trip`() {
        val restored = WidgetSnapshot.decode(parsedSnapshot().encode())!!
        val codex = restored.providers.first { it.providerId == "codex" }

        assertEquals(3, codex.windows.size)
        assertEquals(1, codex.extraWindows.size)
        assertEquals(2, codex.primaryWindows.size)

        val additional = codex.extraWindows.single()
        assertTrue(additional.additional)
        assertEquals("gpt5_codex", additional.limitId)
        assertEquals("gpt-5-codex", additional.label)
        assertEquals(99.0, additional.usedPercent!!, 0.0)

        val zai = restored.providers.first { it.providerId == "zai" }
        assertEquals(300L, zai.windows.first().windowMinutes!!)
        assertEquals(Instant.parse("2026-05-18T08:00:00Z"), zai.windows.first().resetsAt)
    }

    @Test
    fun `the capture time round trips`() {
        val restored = WidgetSnapshot.decode(parsedSnapshot().encode())!!
        assertEquals(capturedAt, restored.capturedAt)
    }

    @Test
    fun `the hub url round trips inside the snapshot body`() {
        // The widget distinguishes "not configured yet" from "Hub returned no
        // quota data" by reading `hubUrl`. When the decoder dropped the field, both
        // cases looked identical and an unconfigured widget always claimed to be
        // unconfigured.
        val original = parsedSnapshot()!!.copy(hubUrl = "https://hub.example.com")
        val restored = WidgetSnapshot.decode(original.encode())
        assertNotNull(restored)
        assertEquals("https://hub.example.com", restored!!.hubUrl)
    }

    @Test
    fun `a snapshot without a hub url decodes to an empty url`() {
        // Legacy shape: snapshots written before the field existed must still load,
        // they simply cannot distinguish the two empty states.
        val legacy = """{"updatedAt":"2026-05-18T00:00:00.000Z","limits":{"providers":[]}}"""
        val restored = WidgetSnapshot.decode(legacy)
        assertNotNull(restored)
        assertEquals("", restored!!.hubUrl)
    }

    @Test
    fun `the empty snapshot round trips`() {
        assertEquals(Instant.EPOCH, WidgetSnapshot.EMPTY.capturedAt)
        assertTrue(WidgetSnapshot.EMPTY.providers.isEmpty())

        val restored = WidgetSnapshot.decode(WidgetSnapshot.EMPTY.encode())
        assertNotNull(restored)
        assertEquals(Instant.EPOCH, restored!!.capturedAt)
        assertTrue(restored.providers.isEmpty())
    }

    @Test
    fun `decoding garbage returns null instead of throwing`() {
        // A widget reads this before every render; a corrupt preference value must
        // degrade to "no data", never crash the launcher process.
        assertNull(WidgetSnapshot.decode("garbage"))
        assertNull(WidgetSnapshot.decode(""))
        assertNull(WidgetSnapshot.decode("<html>nope</html>"))
        assertNull(WidgetSnapshot.decode("[1,2,3]"))
        assertNull(WidgetSnapshot.decode("{\"limits\":"))
    }

    @Test
    fun `a snapshot with no limits decodes to an empty one`() {
        val restored = WidgetSnapshot.decode(
            """{ "updatedAt": "2026-05-18T04:00:00Z", "staleAfterMs": 0, "limits": { "updatedAt": "2026-05-18T04:00:00Z" } }"""
        )
        assertNotNull(restored)
        assertEquals(Instant.parse("2026-05-18T04:00:00Z"), restored!!.capturedAt)
        assertTrue(restored.providers.isEmpty())
    }

    @Test
    fun `isDecodableSnapshot gates the widget read path`() {
        assertTrue(isDecodableSnapshot(parsedSnapshot().encode()))
        assertFalse(isDecodableSnapshot(null))
        assertFalse(isDecodableSnapshot(""))
        assertFalse(isDecodableSnapshot("garbage"))
    }
}
