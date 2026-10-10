package com.tokenmon.mobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ProviderCatalog` is cosmetic by design — it decides no behaviour, only how a
 * row reads. What still matters is that it never returns a blank or wrong-shaped
 * string: upstream adds providers regularly (the catalogue went from 4 to 29
 * entries during 2026), and a viewer that had to be taught each new id would need
 * a release every time one appears.
 */
class ProviderCatalogTest {

    // ------------------------------------------------------- provider names ---

    @Test
    fun `known provider ids map to their display names`() {
        assertEquals("Claude", ProviderCatalog.displayName("claude"))
        assertEquals("GLM", ProviderCatalog.displayName("zai"))
        assertEquals("GLM Team", ProviderCatalog.displayName("zaiteam"))
        assertEquals("Third-party APIs", ProviderCatalog.displayName("thirdparty"))
        assertEquals("Xiaomi MiMo", ProviderCatalog.displayName("mimo"))
    }

    @Test
    fun `provider ids are matched case and whitespace insensitively`() {
        // The Hub lower-cases ids in `normalizeProviderId()`; a viewer that only
        // looked up the exact key would fall back on a row it does know.
        assertEquals("Claude", ProviderCatalog.displayName("CLAUDE"))
        assertEquals("Claude", ProviderCatalog.displayName("  Claude  "))
    }

    @Test
    fun `an unknown provider id is prettified rather than hidden`() {
        val single = ProviderCatalog.displayName("somefutureprovider")
        assertEquals("Somefutureprovider", single)
        assertFalse(single.isBlank())

        assertEquals("Some Future Provider", ProviderCatalog.displayName("some-future-provider"))
        assertEquals("New Thing Pro", ProviderCatalog.displayName("new_thing.pro"))
    }

    @Test
    fun `an empty provider id reads as unknown`() {
        assertEquals("未知 Provider", ProviderCatalog.displayName(""))
        assertEquals("未知 Provider", ProviderCatalog.displayName("   "))
        // An id made only of separators prettifies to nothing, so the raw id is
        // kept rather than rendering an empty heading.
        assertEquals("---", ProviderCatalog.displayName("---"))
    }

    // ------------------------------------------------------- window labels ---

    @Test
    fun `session windows are named per vendor`() {
        // Most vendors publish a rolling session; this whole group publishes it as
        // a five-hour window instead, and calling it "会话" would be wrong.
        assertEquals("会话", ProviderCatalog.windowKindLabel("claude", WindowKind.SESSION))
        assertEquals("会话", ProviderCatalog.windowKindLabel("codex", WindowKind.SESSION))
        assertEquals("5 小时", ProviderCatalog.windowKindLabel("zai", WindowKind.SESSION))
    }

    @Test
    fun `the five-hour group is exactly the documented vendor set`() {
        for (id in listOf(
            "alibaba", "antigravity", "cline", "commandcode",
            "kimi", "volcengine", "zai", "zaiteam"
        )) {
            assertEquals("5 小时", ProviderCatalog.windowKindLabel(id, WindowKind.SESSION))
        }
        // A vendor outside the group must not inherit the label by accident.
        assertEquals("会话", ProviderCatalog.windowKindLabel("opencode", WindowKind.SESSION))
        assertEquals("会话", ProviderCatalog.windowKindLabel("deepseek", WindowKind.SESSION))
    }

    @Test
    fun `non-session window kinds have a fixed label`() {
        assertEquals("每日", ProviderCatalog.windowKindLabel("claude", WindowKind.DAILY))
        assertEquals("每周", ProviderCatalog.windowKindLabel("claude", WindowKind.WEEKLY))
        assertEquals("每月", ProviderCatalog.windowKindLabel("claude", WindowKind.BILLING))
        // The vendor-specific five-hour rule applies to session windows only.
        assertEquals("每日", ProviderCatalog.windowKindLabel("zai", WindowKind.DAILY))
        // UNKNOWN still needs something to render.
        assertEquals("额度", ProviderCatalog.windowKindLabel("claude", WindowKind.UNKNOWN))
    }

    @Test
    fun `an explicit wire label always wins over the kind default`() {
        // docs/API.md: the wire `label` is display-only but authoritative; only a
        // window that declared nothing but its kind is named by the client.
        assertEquals("Weekly all models", ProviderCatalog.windowLabel("claude", "Weekly all models", WindowKind.WEEKLY))
        assertEquals("Usage credits", ProviderCatalog.windowLabel("claude", "Usage credits", WindowKind.BILLING))
        // Even when it contradicts the kind default.
        assertEquals("Custom", ProviderCatalog.windowLabel("claude", "Custom", WindowKind.DAILY))
    }

    @Test
    fun `a blank wire label falls back to the kind default`() {
        assertEquals("会话", ProviderCatalog.windowLabel("claude", "", WindowKind.SESSION))
        assertEquals("会话", ProviderCatalog.windowLabel("claude", "   ", WindowKind.SESSION))
        assertEquals("每周", ProviderCatalog.windowLabel("claude", "", WindowKind.WEEKLY))
        assertEquals("5 小时", ProviderCatalog.windowLabel("zai", "", WindowKind.SESSION))
    }

    // ------------------------------------------------------- status labels ---

    @Test
    fun `a healthy row shows no status badge at all`() {
        // "ok" maps to the empty string on purpose: a badge on every healthy
        // provider would be noise.
        assertEquals("", ProviderCatalog.statusLabel("ok"))
        assertEquals("", ProviderCatalog.statusLabel("  ok  "))
    }

    @Test
    fun `problem statuses get a distinct label`() {
        assertEquals("已限流", ProviderCatalog.statusLabel("rateLimited"))
        assertEquals("数据源限流", ProviderCatalog.statusLabel("sourceRateLimited"))
        assertEquals("需重新登录", ProviderCatalog.statusLabel("unauthorized"))
        assertEquals("未配置", ProviderCatalog.statusLabel("notConfigured"))
        assertEquals("已停用", ProviderCatalog.statusLabel("disabled"))
        assertEquals("不可用", ProviderCatalog.statusLabel("unavailable"))
        assertEquals("异常", ProviderCatalog.statusLabel("error"))
    }

    @Test
    fun `an unknown status degrades to no badge rather than an invented one`() {
        assertEquals("", ProviderCatalog.statusLabel("somethingNew"))
        assertEquals("", ProviderCatalog.statusLabel(""))
    }

    // ------------------------------------------------------- window kind ------

    @Test
    fun `window kind normalises separators and folds the billing aliases`() {
        // Mirrors core.js `normalizeWindowKind()`:
        // `.trim().toLowerCase().replace(/[_\s-]+/g, '')`.
        assertEquals(WindowKind.SESSION, WindowKind.from("session"))
        assertEquals(WindowKind.SESSION, WindowKind.from("  Session "))
        assertEquals(WindowKind.DAILY, WindowKind.from("DAILY"))
        assertEquals(WindowKind.WEEKLY, WindowKind.from("weekly"))
        assertEquals(WindowKind.BILLING, WindowKind.from("billing"))
        assertEquals(WindowKind.BILLING, WindowKind.from("billingCycle"))
        assertEquals(WindowKind.BILLING, WindowKind.from("billing_cycle"))
        assertEquals(WindowKind.BILLING, WindowKind.from("billing cycle"))
        assertEquals(WindowKind.BILLING, WindowKind.from("billing-cycle"))
        assertEquals(WindowKind.BILLING, WindowKind.from("Monthly"))
    }

    @Test
    fun `an unrecognised window kind maps to UNKNOWN so the parser can drop it`() {
        assertEquals(WindowKind.UNKNOWN, WindowKind.from("quarterly"))
        assertEquals(WindowKind.UNKNOWN, WindowKind.from(""))
        assertEquals(WindowKind.UNKNOWN, WindowKind.from("   "))
        assertEquals(WindowKind.UNKNOWN, WindowKind.from(null))
    }

    @Test
    fun `the window kind enum covers every wire kind plus a safe fallback`() {
        assertEquals(5, WindowKind.values().size)
        val reachable = listOf("session", "daily", "weekly", "billing", "monthly", "quarterly")
            .map { WindowKind.from(it) }
            .toSet()
        assertTrue(reachable.containsAll(WindowKind.values().toList()))
    }
}
