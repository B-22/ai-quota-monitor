package com.tokenmon.mobile.data.model

import com.tokenmon.mobile.HubFixtures
import com.tokenmon.mobile.HubFixtures.providerJson
import com.tokenmon.mobile.HubFixtures.statsBody
import com.tokenmon.mobile.HubFixtures.windowJson
import com.tokenmon.mobile.core.LENIENT_JSON
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * `StatsParser` is the app's whole contract with the Hub, so these tests pin the
 * semantics that were read out of the upstream source rather than out of a sample:
 *
 *  - `src/shared/limits/core.js` → `normalizeLimitWindow()` / `percentFromWindow()` /
 *    `clamp()` and `normalizeLimitProvider()`
 *  - `src/shared/hubProtocol.js` → `freshnessEvent()` / `applyFreshnessEvent()`
 *  - `docs/API.md` → the `/api/stats`, `/api/health` and `freshness` wire shapes
 */
class StatsParserTest {

    private fun parse(body: String): HubStats = StatsParser.parseStats(body)

    private fun jsonObject(body: String): JsonObject =
        LENIENT_JSON.parseToJsonElement(body) as JsonObject

    private fun statsProviders(): HubStats = parse(HubFixtures.STATS_BODY)

    private fun provider(id: String, body: String = HubFixtures.STATS_BODY): LimitProvider =
        StatsParser.parseStats(body).providers.first { it.providerId == id }

    // =====================================================================
    // A) parseStats
    // =====================================================================

    @Test
    fun `parses a realistic full stats body`() {
        val stats = statsProviders()

        assertEquals(Instant.parse(HubFixtures.STATS_UPDATED_AT), stats.updatedAt)
        // docs/API.md: `staleAfterMs` is the effective Hub freshness threshold.
        assertEquals(1_500_000L, stats.staleAfterMs)
        assertEquals(Instant.parse(HubFixtures.LIMITS_UPDATED_AT), stats.limitsUpdatedAt)
        assertEquals(300_000L, stats.limitsRefreshMs)
        assertEquals("2026-05-01T00:00:00.000Z", stats.subscriptionsUpdatedAt)

        assertEquals(
            listOf("claude", "zai", "codex", "deepseek", "thirdparty"),
            stats.providers.map { it.providerId }
        )
        assertEquals(0, stats.unreadableProviders.size)

        val claude = stats.providers.first()
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
        assertEquals(Instant.parse("2026-05-18T03:59:00.000Z"), claude.updatedAt)
        assertFalse(claude.stale)
        // accountSummary prefers the email, then the name, then the plan.
        assertEquals("ada@example.com", claude.accountSummary)

        assertEquals(2, stats.devices.size)
        val macbook = stats.devices.first { it.deviceId == "macbook" }
        assertEquals("ada-mbp.local", macbook.hostname)
        assertEquals("darwin-arm64", macbook.platform)
        assertEquals("macOS 26.0", macbook.osName)
        assertFalse(macbook.stale)
        assertEquals(Instant.parse("2026-05-18T03:59:00.000Z"), macbook.updatedAt)
        assertTrue(stats.devices.first { it.deviceId == "desktop" }.stale)
    }

    @Test
    fun `usedPercent is honoured and remainingPercent is 100 minus it rounded to three decimals`() {
        val claude = provider("claude")
        val session = claude.windows.first { it.kind == WindowKind.SESSION }
        assertEquals(42.0, session.usedPercent!!, 0.0)
        assertEquals(58.0, session.remainingPercent!!, 0.0)

        val weekly = claude.windows.first { it.rawKind == "weekly" }
        assertEquals(20.5, weekly.usedPercent!!, 0.0)
        assertEquals(79.5, weekly.remainingPercent!!, 0.0)

        // Upstream: `remainingPercent: Number((100 - usedPercent).toFixed(3))`.
        // 33.333 used must render 66.667 left, i.e. genuinely 3-decimal rounded
        // rather than a plain 100 - used subtraction (which would give 66.667 too,
        // but the third decimal proves the rounding rule is in play at all).
        val threeDp = parse(
            statsBody(
                providerJson(
                    "claude",
                    windowJson("kind" to "\"daily\"", "usedPercent" to "33.333")
                )
            )
        ).providers.first().windows.single()
        assertEquals(33.333, threeDp.usedPercent!!, 1e-9)
        assertEquals(66.667, threeDp.remainingPercent!!, 1e-9)
    }

    @Test
    fun `usedPercent is clamped into zero to one hundred like upstream clamp`() {
        val windows = parse(
            statsBody(
                providerJson(
                    "claude",
                    windowJson("kind" to "\"daily\"", "usedPercent" to "-5"),
                    windowJson("kind" to "\"weekly\"", "usedPercent" to "140"),
                    windowJson("kind" to "\"billing\"", "usedPercent" to "42")
                )
            )
        ).providers.first().windows

        // core.js: `clamp(explicit, 0, 100)` — a provider reporting more than the
        // limit must not render as a >100% meter.
        assertEquals(0.0, windows[0].usedPercent!!, 0.0)
        assertEquals(100.0, windows[0].remainingPercent!!, 0.0)
        assertEquals(100.0, windows[1].usedPercent!!, 0.0)
        assertEquals(0.0, windows[1].remainingPercent!!, 0.0)
        // In-range values pass through untouched (clamp, not a bucket).
        assertEquals(42.0, windows[2].usedPercent!!, 0.0)
    }

    @Test
    fun `percentage is derived from used over limit only when limit is positive`() {
        val windows = parse(
            statsBody(
                providerJson(
                    "claude",
                    windowJson("kind" to "\"daily\"", "used" to "30", "limit" to "120"),
                    windowJson("kind" to "\"weekly\"", "used" to "30", "limit" to "0"),
                    windowJson("kind" to "\"billing\"", "used" to "30")
                )
            )
        ).providers.first().windows

        // percentFromWindow: `used / limit * 100` when limit > 0.
        assertEquals(25.0, windows[0].usedPercent!!, 1e-9)
        assertEquals(75.0, windows[0].remainingPercent!!, 1e-9)

        // limit == 0 and limit absent produce no percentage at all: an absolute
        // amount with no denominator is not a meter, and inventing one would be
        // the client guessing at arithmetic the Hub declined to publish.
        assertNull(windows[1].usedPercent)
        assertNull(windows[1].remainingPercent)
        assertNull(windows[2].usedPercent)
        assertNull(windows[2].remainingPercent)
        assertEquals(30.0, windows[2].used!!, 0.0)
    }

    @Test
    fun `derived percentage is clamped too`() {
        // `used / limit` can exceed 100 (used counted past the limit); core.js
        // clamps the derived value with the same `clamp()` as the explicit one.
        val window = parse(
            statsBody(
                providerJson("claude", windowJson("kind" to "\"weekly\"", "used" to "200", "limit" to "100"))
            )
        ).providers.first().windows.single()
        assertEquals(100.0, window.usedPercent!!, 0.0)
        assertEquals(0.0, window.remainingPercent!!, 0.0)
    }

    @Test
    fun `percentage sent as a string is read after stripping percent and currency marks`() {
        val windows = parse(
            statsBody(
                providerJson(
                    "claude",
                    windowJson("kind" to "\"session\"", "usedPercent" to "\"78%\""),
                    windowJson("kind" to "\"daily\"", "usedPercent" to "\"12.5%\""),
                    windowJson("kind" to "\"weekly\"", "used" to "\"$1,234.50\"")
                )
            )
        ).providers.first().windows

        // core.js `asNumber()`: `Number(value.replace(/[%,$]/g, ''))`. Providers
        // that scrape a dashboard send "78%" / "12.5 %" rather than a number.
        assertEquals(78.0, windows[0].usedPercent!!, 0.0)
        assertEquals(22.0, windows[0].remainingPercent!!, 0.0)
        assertEquals(12.5, windows[1].usedPercent!!, 1e-9)
        assertEquals(1234.5, windows[2].used!!, 1e-9)
    }

    @Test
    fun `a provider missing every optional field still parses`() {
        val row = parse(
            statsBody(
                providerJson("cursor", windowJson("kind" to "\"session\"", "usedPercent" to "10"))
            )
        ).providers.single()

        assertEquals("cursor", row.providerId)
        assertEquals("Cursor", row.displayName)
        // Every optional field degrades to empty/null rather than throwing: the
        // Hub itself emits bare rows for a provider that only published a window.
        assertEquals("", row.accountKey)
        assertEquals("", row.accountName)
        assertEquals("", row.accountEmail)
        assertEquals("", row.accountLabel)
        assertEquals("", row.planLabel)
        assertEquals("", row.workspaceKind)
        assertEquals("", row.status)
        assertEquals("", row.source)
        assertEquals("", row.sourceDeviceId)
        assertNull(row.updatedAt)
        assertNull(row.balanceAmount)
        assertNull(row.balanceCurrency)
        assertNull(row.actionRequired)
        assertNull(row.adapterId)
        assertFalse(row.stale)
        assertNull(row.parseIssue)
        assertEquals("", row.accountSummary)
        assertEquals(1, row.windows.size)
    }

    @Test
    fun `an unknown provider id still renders with a prettified name`() {
        // Upstream `normalizeProviderId()` rejects unknown ids, so the Hub cannot
        // send one today — but the catalog gained providers steadily through 2026
        // and a newer Hub will. The viewer must not go blank when it does.
        val single = parse(statsBody(providerJson("somefutureprovider"))).providers.single()
        assertEquals("somefutureprovider", single.providerId)
        assertEquals("Somefutureprovider", single.displayName)
        assertNull(single.parseIssue)

        val hyphenated = parse(statsBody(providerJson("some-future-provider"))).providers.single()
        assertEquals("Some Future Provider", hyphenated.displayName)
    }

    @Test
    fun `an unknown window kind drops only that window`() {
        // core.js `normalizeWindowKind()` returns null for anything outside
        // session/daily/weekly/billing and `normalizeLimitWindow()` returns null,
        // so the whole window is dropped. The provider itself must survive.
        val row = parse(
            statsBody(
                providerJson(
                    "claude",
                    windowJson("kind" to "\"quarterly\"", "usedPercent" to "10"),
                    windowJson("kind" to "\"session\"", "usedPercent" to "42"),
                    windowJson("usedPercent" to "11")
                )
            )
        ).providers.single()

        assertEquals(1, row.windows.size)
        assertEquals(WindowKind.SESSION, row.windows.single().kind)
        assertEquals(42.0, row.windows.single().usedPercent!!, 0.0)
        assertNull(row.parseIssue)
    }

    @Test
    fun `one malformed provider row does not kill its siblings`() {
        val body = statsBody(
            providerJson("claude", windowJson("kind" to "\"session\"", "usedPercent" to "42")),
            // Not objects: `objectListOrEmpty()` skips them outright.
            "\"i-am-a-string\"",
            "42",
            // An object with no `provider` key: `parseProvider()` throws and the
            // row is replaced by an explicitly-marked unreadable placeholder.
            "{ \"accountKey\": \"sha256:orphan\" }"
        )

        val stats = parse(body)

        assertEquals(2, stats.providers.size)

        val claude = stats.providers.first()
        assertEquals("claude", claude.providerId)
        assertEquals(1, claude.windows.size)
        assertNull(claude.parseIssue)

        val broken = stats.providers[1]
        assertNotNull(broken.parseIssue)
        assertTrue(broken.stale)
        assertTrue(broken.windows.isEmpty())
        // The id is unrecoverable, so the placeholder must not claim to be one.
        assertEquals("unknown", broken.providerId)

        assertEquals(listOf(broken), stats.unreadableProviders)
        // Unreadable rows stay listed *and* visible: a schema change should show up
        // as "1 unreadable provider", not as a silently shorter dashboard. Note the
        // order is NOT the Hub's — `visibleProviders` fronts unreadable rows so the
        // warning sits next to the "unreadable" count rather than at the bottom.
        assertEquals(2, stats.visibleProviders.size)
        assertEquals(setOf("claude", "unknown"), stats.visibleProviders.map { it.providerId }.toSet())
        assertEquals(listOf("unknown", "claude"), stats.visibleProviders.map { it.providerId })
        // Either way, the readable row is present exactly once.
        assertEquals(1, stats.visibleProviders.count { it.providerId == "claude" })
    }

    @Test
    fun `an empty provider id is reported as an unreadable row`() {
        val stats = parse(statsBody(providerJson("")))
        assertEquals(1, stats.providers.size)
        assertNotNull(stats.providers.single().parseIssue)
        assertEquals(1, stats.unreadableProviders.size)
    }

    @Test
    fun `unknown top-level and provider fields are ignored`() {
        // Upstream adds envelope fields continuously (periods, historyPreview,
        // sessionDetailsOmitted, ...). Binding to a generated schema would make
        // each addition a crash; reading named keys makes it a non-event.
        val body = """
        {
          "updatedAt": "2026-05-18T04:00:00.000Z",
          "staleAfterMs": 1500000,
          "brandNewHubField": { "nested": [1, 2, {"deep": true}] },
          "limits": {
            "updatedAt": "2026-05-18T03:59:12.500Z",
            "refreshMs": 300000,
            "providers": [
              {
                "provider": "claude",
                "brandNewProviderField": "ignored",
                "usageSummary": { "requests": 12, "totalTokens": 900 },
                "windows": [
                  { "kind": "session", "usedPercent": 42, "brandNewWindowField": true }
                ]
              }
            ]
          },
          "periods": { "today": { "totalTokens": 1 } },
          "devices": [],
          "sessionsUpdatedAt": "ignored"
        }
        """.trimIndent()

        val stats = parse(body)
        assertEquals(1, stats.providers.size)
        assertEquals(1, stats.providers.single().windows.size)
        assertEquals(42.0, stats.providers.single().windows.single().usedPercent!!, 0.0)
        assertEquals(1_500_000L, stats.staleAfterMs)
    }

    @Test
    fun `a non-object JSON body is rejected as a parse exception`() {
        // Anything that is valid JSON but not an object (array, bare literal) must
        // reach the same "not a Hub response" path.
        for (body in listOf("[1,2,3]", "123", "\"a string\"", "null", "true")) {
            try {
                parse(body)
                fail("expected StatsParseException for $body")
            } catch (expected: StatsParseException) {
                // expected
            }
        }
    }

    @Test
    fun `an HTML error page is rejected as a parse exception`() {
        // A reverse proxy answering 502 with an HTML body instead of the Hub's JSON
        // is the failure this guard exists for.
        try {
            parse("<html><body><h1>502</h1></body></html>")
            fail("expected StatsParseException for an HTML body")
        } catch (expected: StatsParseException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `whitespace bearing HTML becomes a StatsParseException, not a lexer crash`() {
        // A reverse proxy returning an HTML error page for /api/stats is the most
        // likely way to reach this path, and real HTML has newlines and spaces
        // between tokens. That makes kotlinx's lexer throw JsonDecodingException
        // rather than yielding a non-object value, so the "not an object" guard
        // never ran — and because HubClient only catches StatsParseException, the
        // raw SerializationException used to escape to the UI instead of the
        // "数据格式不兼容" result.
        //
        // Both shapes must now surface identically.
        for (html in listOf(
            "<html>\n  <body>\n    <h1>502 Bad Gateway</h1>\n  </body>\n</html>",
            "<html>hello</html>"
        )) {
            val thrown = runCatching { parse(html) }.exceptionOrNull()
            assertNotNull("expected $html to be rejected", thrown)
            assertTrue(
                "HTML must surface as StatsParseException so HubClient can classify it",
                thrown is StatsParseException
            )
        }
    }

    @Test
    fun `health rejects an HTML error page as StatsParseException too`() {
        val html = "<html>\n  <body>502</body>\n</html>"
        val thrown = runCatching { StatsParser.parseHealth(html) }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is StatsParseException)
    }

    @Test
    fun `absent limits providers yields an empty list without crashing`() {
        val noLimits = parse(HubFixtures.STATS_BODY_NO_LIMITS)
        assertTrue(noLimits.providers.isEmpty())
        assertTrue(noLimits.devices.isEmpty())
        assertNull(noLimits.limitsUpdatedAt)
        assertEquals(0L, noLimits.limitsRefreshMs)
        assertEquals(0L, noLimits.staleAfterMs)
        assertEquals("", noLimits.subscriptionsUpdatedAt)
        assertTrue(noLimits.visibleProviders.isEmpty())
        assertTrue(noLimits.unreadableProviders.isEmpty())

        val emptyLimits = parse("""{ "limits": { "refreshMs": 300000 } }""")
        assertTrue(emptyLimits.providers.isEmpty())
        assertEquals(300_000L, emptyLimits.limitsRefreshMs)

        // `providers` present but not an array is equally survivable.
        val wrongType = parse("""{ "limits": { "providers": "nope" } }""")
        assertTrue(wrongType.providers.isEmpty())
    }

    @Test
    fun `a credits window is money and never gets a meter`() {
        // docs/API.md: a `credits` window's headline is money; balance providers
        // publish no percentage at all and any meter percentage is a *renderer*
        // derivation kept off the wire. This row deliberately carries both a
        // percentage and `showMeter: true` to prove the client refuses to render
        // a meter for it even then.
        val window = parse(
            statsBody(
                providerJson(
                    "opencode",
                    windowJson(
                        "kind" to "\"billing\"",
                        "metric" to "\"credits\"",
                        "label" to "\"Balance\"",
                        "usedPercent" to "30",
                        "showMeter" to "true",
                        "remaining" to "12.5",
                        "currency" to "\"USD\""
                    )
                )
            )
        ).providers.single().windows.single()

        assertTrue(window.isMoneyWindow)
        assertFalse(window.hasMeter)
        assertTrue(window.hasMoney)
        // The number is still parsed — it just is not used for a meter.
        assertEquals(30.0, window.usedPercent!!, 0.0)
        assertEquals(70.0, window.remainingPercent!!, 0.0)
    }

    @Test
    fun `a credits window in the realistic fixture has no meter and keeps its boundary kind`() {
        val balance = provider("claude").windows.single { it.metric == "credits" }
        assertTrue(balance.isMoneyWindow)
        // Claude/OpenCode publish the balance with `showMeter: false`: the pool is a
        // sum of independently expiring grants with no quota denominator.
        assertFalse(balance.showMeter)
        assertFalse(balance.hasMeter)
        assertNull(balance.usedPercent)
        assertTrue(balance.hasMoney)
        assertEquals(42.5, balance.remaining!!, 0.0)
        assertEquals("重置 / 到期", balance.boundaryWording)
    }

    @Test
    fun `showMeter false suppresses the meter even for a percentage window`() {
        val window = parse(
            statsBody(
                providerJson(
                    "claude",
                    windowJson(
                        "kind" to "\"billing\"",
                        "metric" to "\"spend\"",
                        "label" to "\"Usage credits\"",
                        "usedPercent" to "30",
                        "showMeter" to "false"
                    )
                )
            )
        ).providers.single().windows.single()

        // `showMeter !== false` upstream: an explicit false means "there is no
        // denominator", which must be honoured rather than papered over.
        assertFalse(window.isMoneyWindow)
        assertFalse(window.showMeter)
        assertFalse(window.hasMeter)
        assertEquals(30.0, window.usedPercent!!, 0.0)
    }

    @Test
    fun `additional windows are separated from the primary ones`() {
        val codex = provider("codex")
        assertEquals(3, codex.windows.size)
        assertEquals(2, codex.primaryWindows.size)
        assertEquals(1, codex.extraWindows.size)

        val extra = codex.extraWindows.single()
        assertTrue(extra.additional)
        // docs/API.md: Codex's separately metered buckets carry `additional: true`
        // so compact readers can exclude them without reading the display label.
        assertEquals("gpt5_codex", extra.limitId)
        assertEquals("gpt-5-codex", extra.label)
        assertTrue(codex.primaryWindows.none { it.additional })
        assertTrue(codex.extraWindows.none { !it.additional })
    }

    @Test
    fun `boundaryKind maps to the right wording`() {
        val windows = parse(
            statsBody(
                providerJson(
                    "claude",
                    windowJson("kind" to "\"daily\"", "boundaryKind" to "\"expiry\""),
                    windowJson("kind" to "\"weekly\"", "boundaryKind" to "\"mixed\""),
                    windowJson("kind" to "\"billing\"", "boundaryKind" to "\"reset\""),
                    windowJson("kind" to "\"session\"")
                )
            )
        ).providers.single().windows

        // The type changes wording only, never scheduling.
        assertEquals("expiry", windows[0].boundaryKind)
        assertEquals("到期", windows[0].boundaryWording)
        assertEquals("mixed", windows[1].boundaryKind)
        assertEquals("重置 / 到期", windows[1].boundaryWording)
        assertEquals("reset", windows[2].boundaryKind)
        assertEquals("重置", windows[2].boundaryWording)
        // Omitted means the legacy reset presentation.
        assertNull(windows[3].boundaryKind)
        assertEquals("重置", windows[3].boundaryWording)
    }

    @Test
    fun `boundaryKind is trimmed and lowercased before matching`() {
        val window = parse(
            statsBody(
                providerJson("claude", windowJson("kind" to "\"daily\"", "boundaryKind" to "\"  Mixed  \""))
            )
        ).providers.single().windows.single()
        assertEquals("mixed", window.boundaryKind)
        assertEquals("重置 / 到期", window.boundaryWording)
    }

    @Test
    fun `resetsAt accepts Z fractional and offset forms and preserves the raw text`() {
        val windows = parse(
            statsBody(
                providerJson(
                    "claude",
                    windowJson("kind" to "\"session\"", "resetsAt" to "\"2026-05-18T05:00:00.000Z\""),
                    windowJson("kind" to "\"daily\"", "resetsAt" to "\"2026-05-18T05:00:00.123456Z\""),
                    windowJson("kind" to "\"weekly\"", "resetsAt" to "\"2026-05-18T13:00:00+08:00\""),
                    windowJson("kind" to "\"billing\"", "resetsAt" to "\"whenever\""),
                    windowJson("kind" to "\"session\"")
                )
            )
        ).providers.first().windows

        assertEquals(Instant.parse("2026-05-18T05:00:00Z"), windows[0].resetsAt)
        // Sub-second precision is kept: the Hub's own reset re-read boundary is 30s.
        assertEquals(123_456_000, windows[1].resetsAt!!.nano)
        assertEquals(Instant.parse("2026-05-18T05:00:00Z"), windows[2].resetsAt)
        // A value the client cannot read must not cost the whole window; the raw
        // text is kept so a reader can still show the Hub's own wording.
        assertNull(windows[3].resetsAt)
        assertEquals("whenever", windows[3].resetsAtRaw)
        assertNull(windows[4].resetsAt)
        assertEquals("", windows[4].resetsAtRaw)
    }

    @Test
    fun `provider stale flag is read`() {
        // `stale` is added by the Hub's cross-device aggregation (`aggregateLimits`)
        // and is absent on a per-device record, so it must default to false.
        assertFalse(provider("claude").stale)
        assertTrue(provider("deepseek").stale)
    }

    @Test
    fun `balance blocks and adapter hints are read`() {
        val deepseek = provider("deepseek")
        // docs/API.md: `balance` is the native-currency block.
        assertEquals(18.25, deepseek.balanceAmount!!, 1e-9)
        // A block written as `"currency": "cny"` is normalised to upper case, matching
        // what upstream `normalizeProviderBalance()` does and matching how
        // `windows[].currency` is already handled in this parser. Without it, a Hub
        // that did not upper-case would leak a lower-case code into
        // `LimitProvider.balanceCurrency`.
        assertEquals("CNY", deepseek.balanceCurrency)
        // The claude credits window's currency is normalised to upper case.
        assertEquals(
            "USD",
            provider("claude").windows.single { it.metric == "credits" }.currency
        )
        // `balanceUsd` is the legacy flat field, kept for readers predating the block.
        assertEquals(7.5, provider("thirdparty").balanceAmount!!, 1e-9)
        assertEquals("newapi-account", provider("thirdparty").adapterId)
        assertNull(provider("claude").adapterId)
    }

    @Test
    fun `blank action hints normalise to null`() {
        val row = parse(
            statsBody(
                providerJson(
                    "antigravity",
                    extra = "\"actionRequired\": \"  \", \"adapterId\": \"\""
                )
            )
        ).providers.single()
        assertNull(row.actionRequired)
        assertNull(row.adapterId)
    }

    @Test
    fun `window labels fall back to the catalog when the wire label is absent`() {
        val claude = provider("claude")
        // A blank wire label means "the Hub published no label"; the window-kind
        // default is then used rather than an empty heading.
        assertEquals("会话", claude.primaryWindows.first().label)
        assertEquals("Weekly all models", claude.windows.first { it.rawKind == "weekly" }.label)

        // Z.ai publishes its rolling window as 5-hour, so the fallback differs.
        assertEquals("5 小时", provider("zai").primaryWindows.first().label)
    }

    // =====================================================================
    // B) parseHealth
    // =====================================================================

    @Test
    fun `parses a full health body including hubBuild`() {
        val health = StatsParser.parseHealth(HubFixtures.HEALTH_BODY)
        assertTrue(health.ok)
        assertEquals("hub", health.role)
        assertEquals("cloudflare-worker", health.runtime)
        assertEquals(1, health.version)
        assertEquals(2, health.deviceCount)
        assertTrue(health.secretRequired)

        val build = health.hubBuild!!
        assertEquals(1, build.schemaVersion)
        assertEquals("cloudflare-worker", build.runtime)
        assertEquals("sha256:core0001", build.coreBuildId)
        assertEquals("sha256:runtime0001", build.runtimeBuildId)
    }

    @Test
    fun `a legacy health body without hubBuild parses with a null build`() {
        // docs/API.md: "A health response without `hubBuild` is a legacy Hub and
        // remains otherwise compatible."
        val health = StatsParser.parseHealth(HubFixtures.HEALTH_BODY_LEGACY)
        assertNull(health.hubBuild)
        assertTrue(health.ok)
        assertEquals("hub", health.role)
        assertEquals("node", health.runtime)
        assertEquals(1, health.deviceCount)
    }

    @Test
    fun `a malformed hubBuild is unrecognized rather than a crash`() {
        // docs/API.md: "present but malformed metadata is unrecognized instead of
        // being treated as legacy" — both cases are null here, but only one of them
        // may throw.
        val health = StatsParser.parseHealth(HubFixtures.HEALTH_BODY_MALFORMED_BUILD)
        assertNull(health.hubBuild)
        // The rest of the response is still usable.
        assertEquals(1, health.deviceCount)
        assertFalse(health.secretRequired)

        // A non-object `hubBuild` is the documented "unrecognized" case. An empty
        // object is deliberately NOT in this list: it is structurally valid, so the
        // reader treats it as a present-but-blank marker rather than a parse error.
        for (raw in listOf("42", "[]", "\"garbage\"", "null")) {
            val body = """{ "ok": true, "hubBuild": $raw, "deviceCount": 0 }"""
            assertNull("hubBuild=$raw", StatsParser.parseHealth(body).hubBuild)
        }

        val blank = StatsParser.parseHealth("""{ "ok": true, "hubBuild": {} }""").hubBuild
        assertNotNull(blank)
        assertNull(blank!!.schemaVersion)
        assertEquals("", blank.coreBuildId)
    }

    @Test
    fun `health defaults are applied for absent fields`() {
        val health = StatsParser.parseHealth("{}")
        assertFalse(health.ok)
        assertEquals("", health.role)
        assertEquals("", health.runtime)
        assertNull(health.version)
        assertEquals(0, health.deviceCount)
        assertFalse(health.secretRequired)
        assertNull(health.hubBuild)
    }

    @Test
    fun `a non-object health body is rejected`() {
        try {
            StatsParser.parseHealth("<html><body>oops</body></html>")
            fail("expected StatsParseException")
        } catch (expected: StatsParseException) {
            // expected
        }
    }

    // =====================================================================
    // C) applyFreshness
    // =====================================================================

    @Test
    fun `freshness merge updates envelope and per-device timestamps`() {
        val current = statsProviders()
        val merged = StatsParser.applyFreshness(current, jsonObject(HubFixtures.FRESHNESS_STATS_BODY))

        assertEquals(Instant.parse("2026-05-18T04:30:00.000Z"), merged.updatedAt)
        assertEquals(2_400_000L, merged.staleAfterMs)
        assertEquals(Instant.parse("2026-05-18T04:29:40.000Z"), merged.limitsUpdatedAt)
        assertEquals(2, merged.devices.size)

        // `desktop` is not in the patch, so its row is untouched.
        assertEquals(current.devices[1], merged.devices[1])

        val macbook = merged.devices.first { it.deviceId == "macbook" }
        assertEquals(Instant.parse("2026-05-18T04:29:00.000Z"), macbook.updatedAt)
        assertTrue(macbook.stale)
        // Identity fields are never carried by a freshness frame.
        assertEquals("ada-mbp.local", macbook.hostname)
    }

    @Test
    fun `freshness merge preserves providers and devices the patch does not mention`() {
        val current = statsProviders()
        val merged = StatsParser.applyFreshness(current, jsonObject(HubFixtures.FRESHNESS_STATS_BODY))

        // CRITICAL: a `freshness` frame carries timestamps only. Replacing the
        // providers with it would blank every quota number on screen, which is
        // exactly the regression `applyFreshnessEvent()` exists to prevent.
        assertSame(current.providers, merged.providers)
        assertEquals(current.providers, merged.providers)
        assertEquals(5, merged.providers.size)
        // Including window-level detail: a freshness merge must not flatten or drop
        // a provider's windows.
        assertEquals(3, merged.providers.first().windows.size)
        assertEquals(58.0, merged.providers.first().windows.first().remainingPercent!!, 0.0)

        assertEquals(2, merged.devices.size)
        assertEquals(current.devices[1], merged.devices[1])
    }

    @Test
    fun `a null freshness frame returns the current snapshot unchanged`() {
        val current = statsProviders()
        val merged = StatsParser.applyFreshness(current, null)
        assertSame(current, merged)
        assertEquals(current, merged)
    }

    @Test
    fun `an empty freshness frame keeps every existing value`() {
        val current = statsProviders()
        val merged = StatsParser.applyFreshness(current, jsonObject("{}"))
        assertEquals(current, merged)
        // `devices` is rebuilt via `map`, so identity is not preserved even though
        // every element is the same instance.
        assertSame(current.providers, merged.providers)
        assertTrue(merged.devices.zip(current.devices).all { (a, b) -> a === b })
    }

    @Test
    fun `a partial freshness frame does not clear the fields it omits`() {
        val current = statsProviders()
        // Only `limits.updatedAt` arrived.
        val merged = StatsParser.applyFreshness(
            current,
            jsonObject("""{ "limits": { "updatedAt": "2026-05-18T05:00:00.000Z" } }""")
        )
        assertEquals(Instant.parse("2026-05-18T05:00:00.000Z"), merged.limitsUpdatedAt)
        assertEquals(current.updatedAt, merged.updatedAt)
        assertEquals(current.staleAfterMs, merged.staleAfterMs)
        assertEquals(current.devices, merged.devices)
    }

    @Test
    fun `a device patch with only a stale flag keeps its previous timestamp`() {
        val current = statsProviders()
        val merged = StatsParser.applyFreshness(
            current,
            jsonObject("""{ "devices": [ { "deviceId": "macbook", "stale": true } ] }""")
        )
        val macbook = merged.devices.first { it.deviceId == "macbook" }
        assertTrue(macbook.stale)
        assertEquals(Instant.parse("2026-05-18T03:59:00.000Z"), macbook.updatedAt)
    }
}
