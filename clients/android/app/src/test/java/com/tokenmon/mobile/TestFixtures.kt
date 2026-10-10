package com.tokenmon.mobile

import com.tokenmon.mobile.data.model.DeviceInfo
import com.tokenmon.mobile.data.model.HubStats
import com.tokenmon.mobile.data.model.LimitProvider
import com.tokenmon.mobile.data.model.LimitWindow
import com.tokenmon.mobile.data.model.WindowKind
import java.time.Instant

/**
 * Shared wire fixtures + model builders for the JVM unit tests.
 *
 * The JSON here is not invented for the tests: it is the shape documented in the
 * upstream `docs/API.md` (the `/api/health`, `/api/ingest` `limits` block and the
 * `GET /api/stats` envelope) crossed with the field set that
 * `src/shared/limits/core.js` → `normalizeLimitWindow()` / `normalizeLimitProvider()`
 * actually publishes. Every window carries the documented keys
 * (`kind`, `usedPercent`, `resetsAt`, `label`, `metric`, `additional`,
 * `boundaryKind`, `currency`, `showMeter`, `limitId`) so a schema regression in the
 * parser shows up here rather than on the dashboard.
 *
 * Nothing in this file touches `android.*` — it is plain Kotlin so the whole file
 * is usable from a plain JVM test run.
 */
object HubFixtures {

    // ---------------------------------------------------------------- times --

    const val STATS_UPDATED_AT = "2026-05-18T04:00:00.000Z"
    const val LIMITS_UPDATED_AT = "2026-05-18T03:59:12.500Z"

    val STATS_UPDATED_AT_INSTANT: Instant = Instant.parse(STATS_UPDATED_AT)
    val LIMITS_UPDATED_AT_INSTANT: Instant = Instant.parse(LIMITS_UPDATED_AT)

    // ------------------------------------------------------------ full body --

    /**
     * A `GET /api/stats` body with three healthy providers, one stale one and the
     * documented envelope. Deliberately includes:
     *  - a session window with **no** `label` (the Hub's label normalizer returns
     *    `""` and the client must fall back to the window-kind default),
     *  - a `credits` window (money, `showMeter: false`, `boundaryKind: "mixed"`),
     *  - an `additional: true` bucket (Codex's separately metered feature lanes).
     */
    val STATS_BODY: String = """
{
  "updatedAt": "2026-05-18T04:00:00.000Z",
  "staleAfterMs": 1500000,
  "limits": {
    "updatedAt": "2026-05-18T03:59:12.500Z",
    "refreshMs": 300000,
    "providers": [
      {
        "provider": "claude",
        "accountKey": "sha256:claude-account-0001",
        "accountName": "Ada Lovelace",
        "accountEmail": "ada@example.com",
        "accountLabel": "Ada",
        "planLabel": "Max",
        "workspaceKind": "personal",
        "status": "ok",
        "source": "oauth",
        "updatedAt": "2026-05-18T03:59:00.000Z",
        "sourceDeviceId": "macbook",
        "windows": [
          {
            "kind": "session",
            "usedPercent": 42,
            "resetsAt": "2026-05-18T05:00:00.000Z"
          },
          {
            "kind": "weekly",
            "label": "Weekly all models",
            "usedPercent": 20.5,
            "limitId": "weekly_all",
            "resetsAt": "2026-05-25T00:00:00.000Z"
          },
          {
            "kind": "billing",
            "metric": "credits",
            "label": "Balance",
            "remaining": 42.5,
            "currency": "usd",
            "showMeter": false,
            "boundaryKind": "mixed",
            "resetsAt": "2026-06-01T00:00:00.000Z"
          }
        ]
      },
      {
        "provider": "zai",
        "accountKey": "sha256:zai-account-0002",
        "accountName": "Z.ai User",
        "planLabel": "Start",
        "status": "ok",
        "source": "api",
        "updatedAt": "2026-05-18T03:58:00.000Z",
        "sourceDeviceId": "phone",
        "windows": [
          {
            "kind": "session",
            "usedPercent": 78,
            "windowMinutes": 300,
            "resetsAt": "2026-05-18T08:00:00.000Z"
          },
          {
            "kind": "weekly",
            "usedPercent": 12,
            "resetsAt": "2026-05-20T00:00:00.000Z"
          }
        ]
      },
      {
        "provider": "codex",
        "accountKey": "sha256:codex-account-0003",
        "status": "ok",
        "source": "oauth",
        "updatedAt": "2026-05-18T03:57:00.000Z",
        "sourceDeviceId": "macbook",
        "windows": [
          {
            "kind": "session",
            "usedPercent": 5,
            "resetsAt": "2026-05-19T00:00:00.000Z"
          },
          {
            "kind": "weekly",
            "label": "Weekly",
            "usedPercent": 60,
            "limitId": "weekly_all",
            "resetsAt": "2026-05-25T00:00:00.000Z"
          },
          {
            "kind": "weekly",
            "label": "gpt-5-codex",
            "limitId": "gpt5_codex",
            "additional": true,
            "usedPercent": 99,
            "resetsAt": "2026-05-25T00:00:00.000Z"
          }
        ]
      },
      {
        "provider": "deepseek",
        "accountKey": "sha256:deepseek-account-0004",
        "status": "ok",
        "source": "api",
        "sourceDeviceId": "desktop",
        "stale": true,
        "balance": { "amount": 18.25, "currency": "cny" },
        "windows": [
          {
            "kind": "billing",
            "metric": "credits",
            "label": "Balance",
            "remaining": 18.25,
            "currency": "CNY",
            "boundaryKind": "expiry"
          }
        ]
      },
      {
        "provider": "thirdparty",
        "adapterId": "newapi-account",
        "accountKey": "sha256:thirdparty-0005",
        "status": "ok",
        "source": "api",
        "sourceDeviceId": "phone",
        "balanceUsd": 7.5,
        "windows": [
          {
            "kind": "billing",
            "metric": "credits",
            "label": "Balance",
            "remaining": 7.5,
            "currency": "USD",
            "showMeter": false
          }
        ]
      }
    ]
  },
  "devices": [
    {
      "deviceId": "macbook",
      "hostname": "ada-mbp.local",
      "platform": "darwin-arm64",
      "osName": "macOS 26.0",
      "stale": false,
      "updatedAt": "2026-05-18T03:59:00.000Z"
    },
    {
      "deviceId": "desktop",
      "hostname": "tower.local",
      "platform": "win32-x64",
      "osName": "Windows 11",
      "stale": true,
      "updatedAt": "2026-05-17T22:00:00.000Z"
    }
  ],
  "subscriptionsUpdatedAt": "2026-05-01T00:00:00.000Z"
}
""".trimIndent()

    // -------------------------------------------------------------- health ---

    /** `GET /api/health`, verbatim from `docs/API.md` (minus the `now` field). */
    val HEALTH_BODY: String = """
{
  "ok": true,
  "role": "hub",
  "runtime": "cloudflare-worker",
  "version": 1,
  "hubBuild": {
    "schemaVersion": 1,
    "runtime": "cloudflare-worker",
    "coreRevision": 1,
    "coreBuildId": "sha256:core0001",
    "runtimeRevision": 1,
    "runtimeBuildId": "sha256:runtime0001"
  },
  "deviceCount": 2,
  "secretRequired": true,
  "now": "2026-05-18T00:00:00.000Z"
}
""".trimIndent()

    /** A Hub predating the build marker: `hubBuild` absent, "otherwise compatible". */
    val HEALTH_BODY_LEGACY: String = """
{
  "ok": true,
  "role": "hub",
  "runtime": "node",
  "version": 1,
  "deviceCount": 1,
  "secretRequired": true
}
""".trimIndent()

    /** `hubBuild` present but not an object — "unrecognized", not "legacy". */
    val HEALTH_BODY_MALFORMED_BUILD: String = """
{
  "ok": true,
  "role": "hub",
  "runtime": "node",
  "version": 1,
  "hubBuild": "garbage",
  "deviceCount": 1,
  "secretRequired": false
}
""".trimIndent()

    // ----------------------------------------------------------- freshness ---

    /**
     * The inner `stats` object of an upstream `freshness` SSE frame
     * (`freshnessEvent()` in `src/shared/hubProtocol.js`): envelope timestamps
     * plus a per-device `updatedAt`/`stale` pair, and nothing else.
     */
    val FRESHNESS_STATS_BODY: String = """
{
  "updatedAt": "2026-05-18T04:30:00.000Z",
  "staleAfterMs": 2400000,
  "limits": { "updatedAt": "2026-05-18T04:29:40.000Z" },
  "devices": [
    { "deviceId": "macbook", "updatedAt": "2026-05-18T04:29:00.000Z", "stale": true },
    { "deviceId": "phone", "updatedAt": "2026-05-18T04:30:00.000Z", "stale": false }
  ]
}
""".trimIndent()

    // ------------------------------------------------------- JSON builders ---

    /** One `limits.providers[]` entry. [extra] is spliced in verbatim. */
    fun providerJson(
        provider: String = "claude",
        vararg windows: String,
        extra: String = ""
    ): String = buildString {
        append("{")
        append("\"provider\": \"").append(provider).append("\"")
        if (windows.isNotEmpty()) {
            append(", \"windows\": [").append(windows.joinToString(", ")).append("]")
        }
        if (extra.isNotBlank()) append(", ").append(extra)
        append("}")
    }

    /** One `limits.providers[].windows[]` entry, from ordered `key: value` pairs. */
    fun windowJson(vararg fields: Pair<String, String>): String =
        fields.joinToString(prefix = "{", postfix = "}") { "\"${it.first}\": ${it.second}" }

    /** A complete `/api/stats` envelope around the supplied provider rows. */
    fun statsBody(vararg providers: String): String = buildString {
        append("{")
        append("\"updatedAt\": \"").append(STATS_UPDATED_AT).append("\", ")
        append("\"staleAfterMs\": 1500000, ")
        append("\"limits\": {")
        append("\"updatedAt\": \"").append(LIMITS_UPDATED_AT).append("\", ")
        append("\"refreshMs\": 300000")
        if (providers.isNotEmpty()) {
            append(", \"providers\": [").append(providers.joinToString(", ")).append("]")
        }
        append("}, ")
        append("\"devices\": []")
        append("}")
    }

    /** A stats body with no `limits` object at all. */
    val STATS_BODY_NO_LIMITS: String = """
{"updatedAt": "2026-05-18T04:00:00.000Z", "devices": []}
""".trimIndent()
}

/** Builders for the parsed model, so tests can state only the fields they care about. */
object TestModels {

    fun window(
        kind: WindowKind = WindowKind.SESSION,
        rawKind: String = kind.name.lowercase(),
        label: String = "",
        metric: String? = null,
        usedPercent: Double? = null,
        remainingPercent: Double? = null,
        used: Double? = null,
        limit: Double? = null,
        remaining: Double? = null,
        currency: String? = null,
        resetsAt: Instant? = null,
        resetsAtRaw: String = resetsAt?.toString().orEmpty(),
        resetDescription: String = "",
        showMeter: Boolean = true,
        boundaryKind: String? = null,
        additional: Boolean = false,
        limitId: String? = null,
        detail: String = "",
        windowMinutes: Long? = null
    ): LimitWindow = LimitWindow(
        kind = kind,
        rawKind = rawKind,
        label = label,
        metric = metric,
        usedPercent = usedPercent,
        remainingPercent = remainingPercent,
        used = used,
        limit = limit,
        remaining = remaining,
        currency = currency,
        resetsAt = resetsAt,
        resetsAtRaw = resetsAtRaw,
        resetDescription = resetDescription,
        showMeter = showMeter,
        boundaryKind = boundaryKind,
        additional = additional,
        limitId = limitId,
        detail = detail,
        windowMinutes = windowMinutes
    )

    fun provider(
        providerId: String = "claude",
        displayName: String = "Claude",
        accountKey: String = "",
        accountName: String = "",
        accountEmail: String = "",
        accountLabel: String = "",
        planLabel: String = "",
        workspaceKind: String = "",
        status: String = "ok",
        source: String = "",
        updatedAt: Instant? = null,
        windows: List<LimitWindow> = emptyList(),
        balanceAmount: Double? = null,
        balanceCurrency: String? = null,
        stale: Boolean = false,
        sourceDeviceId: String = "",
        actionRequired: String? = null,
        adapterId: String? = null,
        parseIssue: String? = null
    ): LimitProvider = LimitProvider(
        providerId = providerId,
        displayName = displayName,
        accountKey = accountKey,
        accountName = accountName,
        accountEmail = accountEmail,
        accountLabel = accountLabel,
        planLabel = planLabel,
        workspaceKind = workspaceKind,
        status = status,
        source = source,
        updatedAt = updatedAt,
        windows = windows,
        balanceAmount = balanceAmount,
        balanceCurrency = balanceCurrency,
        stale = stale,
        sourceDeviceId = sourceDeviceId,
        actionRequired = actionRequired,
        adapterId = adapterId,
        parseIssue = parseIssue
    )

    fun stats(
        updatedAt: Instant? = null,
        staleAfterMs: Long = 0L,
        limitsUpdatedAt: Instant? = null,
        limitsRefreshMs: Long = 0L,
        providers: List<LimitProvider> = emptyList(),
        devices: List<DeviceInfo> = emptyList(),
        subscriptionsUpdatedAt: String = ""
    ): HubStats = HubStats(
        updatedAt = updatedAt,
        staleAfterMs = staleAfterMs,
        limitsUpdatedAt = limitsUpdatedAt,
        limitsRefreshMs = limitsRefreshMs,
        providers = providers,
        devices = devices,
        subscriptionsUpdatedAt = subscriptionsUpdatedAt
    )
}
