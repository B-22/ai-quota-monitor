package com.tokenmon.mobile.data.model

/**
 * Quota window kinds as normalized by the Hub
 * (`src/shared/limits/core.js` → `normalizeWindowKind`).
 *
 * The Hub folds `billingCycle` / `monthly` into [BILLING] and drops anything it
 * does not recognize. [UNKNOWN] exists so a future kind that survives some
 * intermediate layer still renders with its label instead of vanishing.
 */
enum class WindowKind {
    SESSION,
    DAILY,
    WEEKLY,
    BILLING,
    UNKNOWN;

    companion object {
        fun from(raw: String?): WindowKind {
            val key = raw?.trim()?.lowercase()?.replace("[_\\s-]+".toRegex(), "") ?: return UNKNOWN
            return when (key) {
                "session" -> SESSION
                "daily" -> DAILY
                "weekly" -> WEEKLY
                "billing", "billingcycle", "monthly" -> BILLING
                else -> UNKNOWN
            }
        }
    }
}

/**
 * One quota window on a provider account.
 *
 * Field set mirrors `normalizeLimitWindow()` in the Hub. Everything the Hub
 * marks optional is nullable here rather than defaulted, because "the Hub did not
 * send this" and "the Hub sent zero" are different facts and the UI shows them
 * differently.
 */
data class LimitWindow(
    val kind: WindowKind,
    val rawKind: String,
    val label: String,
    val metric: String?,
    val usedPercent: Double?,
    val remainingPercent: Double?,
    val used: Double?,
    val limit: Double?,
    val remaining: Double?,
    val currency: String?,
    val resetsAt: java.time.Instant?,
    val resetsAtRaw: String,
    val resetDescription: String,
    val showMeter: Boolean,
    val boundaryKind: String?,
    val additional: Boolean,
    val limitId: String?,
    val detail: String,
    val windowMinutes: Long?
) {
    /**
     * A `credits` window's headline number is money, not a percentage: the Hub
     * deliberately keeps any derived percentage off the wire for balance
     * providers. `showMeter: false` is the Hub's own "there is no denominator"
     * signal and must be honoured rather than papered over.
     */
    val isMoneyWindow: Boolean get() = metric == "credits"

    val hasMeter: Boolean get() = showMeter && !isMoneyWindow && usedPercent != null

    val hasMoney: Boolean get() = remaining != null || used != null || limit != null

    /** `expiry` / `mixed` change the wording only, never the scheduling. */
    val boundaryWording: String
        get() = when (boundaryKind) {
            "expiry" -> "到期"
            "mixed" -> "重置 / 到期"
            else -> "重置"
        }
}

/** Hub-reported reset cards. Dates do not imply a count or authorize a reset. */
data class ResetCredits(
    val availableCount: Long?,
    val nextExpiresAt: java.time.Instant?,
    val expirations: List<java.time.Instant>
)

/**
 * One provider account as aggregated by `GET /api/stats` → `limits.providers[]`.
 *
 * [parseIssue] is non-null when this single row could not be read. Such a row is
 * still listed, marked, so a schema change shows up as "1 unreadable provider"
 * instead of silently shrinking the dashboard.
 */
data class LimitProvider(
    val providerId: String,
    val displayName: String,
    val accountKey: String,
    val accountName: String,
    val accountEmail: String,
    val accountLabel: String,
    val planLabel: String,
    val workspaceKind: String,
    val status: String,
    val source: String,
    val updatedAt: java.time.Instant?,
    val windows: List<LimitWindow>,
    val balanceAmount: Double?,
    val balanceCurrency: String?,
    val stale: Boolean,
    val sourceDeviceId: String,
    val actionRequired: String?,
    val adapterId: String?,
    val parseIssue: String? = null,
    val resetCredits: ResetCredits? = null
) {
    /** The identity line under the provider name, best available label first. */
    val accountSummary: String
        get() = accountEmail.ifEmpty { accountName }
            .ifEmpty { planLabel }
            .ifEmpty { accountLabel }
            .ifEmpty { shortAccountKey }

    val hasShortKey: Boolean get() = accountKey.isNotEmpty()

    private val shortAccountKey: String
        get() = if (accountKey.length > 14) accountKey.take(11) + "…" else accountKey

    /** Rows the dashboard draws: everything except buckets the Hub marks extra. */
    val primaryWindows: List<LimitWindow> get() = windows.filter { !it.additional }

    val extraWindows: List<LimitWindow> get() = windows.filter { it.additional }

    /**
     * Does this row say anything the user cares about?
     *
     * An agent reports *every* provider in the catalog, not just the ones it is
     * configured for: a machine with only Codex installed still comes back with
     * 29 rows, 28 of them `notConfigured` with no windows and no balance.
     * Rendering those as cards would bury the one row that matters, so they are
     * filtered out here and counted in the empty state instead.
     */
    val hasContent: Boolean
        get() = windows.isNotEmpty() ||
            balanceAmount != null ||
            resetCredits != null ||
            (status.isNotEmpty() && status != "notConfigured")
}

/** The subset of `stats.devices[]` the phone dashboard has any use for. */
data class DeviceInfo(
    val deviceId: String,
    val hostname: String,
    val platform: String,
    val osName: String,
    val stale: Boolean,
    val updatedAt: java.time.Instant?
)

/**
 * The parsed `GET /api/stats` body, reduced to what a phone dashboard shows.
 *
 * Deliberately lossy: token/session/project accounting stays in the Hub. Keeping
 * an unreadable field out of this model is what makes an upstream field addition
 * a non-event here.
 */
data class HubStats(
    val updatedAt: java.time.Instant?,
    val staleAfterMs: Long,
    val limitsUpdatedAt: java.time.Instant?,
    val limitsRefreshMs: Long,
    val providers: List<LimitProvider>,
    val devices: List<DeviceInfo>,
    val subscriptionsUpdatedAt: String
) {
    val unreadableProviders: List<LimitProvider> get() = providers.filter { it.parseIssue != null }

    /**
     * Providers worth putting on screen.
     *
     * Unparseable rows are placed **first** on purpose: they are the reason the
     * user is looking at the screen, and putting them adjacent to the "N rows
     * could not be parsed" footer explains itself. The Hub's own ordering is
     * preserved among the readable rows.
     */
    val visibleProviders: List<LimitProvider>
        get() = unreadableProviders + providers.filter { it.parseIssue == null && it.hasContent }

    /**
     * Rows the agent reported but that carry nothing to show.
     *
     * Non-zero means the Hub is alive and reporting, just without any configured
     * AI tool on the machines it watches — a different message from "no devices
     * have reported at all".
     */
    val unconfiguredProviderCount: Int
        get() = providers.count { it.parseIssue == null && !it.hasContent }

    /**
     * The newest timestamp that reflects **collected data**, not response generation.
     *
     * `updatedAt` is useless for a freshness display: `aggregateDevices()` sets it
     * to `new Date()` on every `getStats()` call, so it reads "just now" even
     * after every device has been silent for hours. Using it would make a dead
     * collector look alive.
     *
     * So the anchor is the freshest of the limits timestamp and the device
     * reports, which only advance when something actually reports. When nothing
     * has ever reported, it falls back to `updatedAt` rather than showing an
     * empty age.
     */
    val dataTimestamp: java.time.Instant?
        get() {
            val candidates = buildList {
                limitsUpdatedAt?.let { add(it) }
                devices.mapNotNull { it.updatedAt }.forEach { add(it) }
            }
            return candidates.maxOrNull() ?: updatedAt
        }

    /**
     * Identity of the parts a glanceable dashboard actually shows.
     *
     * Token totals are deliberately excluded: they change on every 2s report, so
     * including them would make this constant and defeat the coalescing in
     * [com.tokenmon.mobile.data.HubRepository]. What is included is what the user
     * would notice changing on screen — a quota percentage, a reset time, a
     * provider going stale.
     */
    fun contentFingerprint(): String = buildString {
        providers.forEach { p ->
            append(p.providerId).append('|').append(p.accountKey).append('|')
            append(p.status).append('|').append(p.stale).append('|')
            append(p.parseIssue).append('|')
            p.balanceAmount?.let { append(it) }
            append('|').append(p.balanceCurrency)
            append('|').append(p.resetCredits)
            p.windows.forEach { w ->
                append(';').append(w.rawKind).append(',').append(w.label).append(',')
                append(w.usedPercent).append(',').append(w.remainingPercent).append(',')
                append(w.used).append(',').append(w.limit).append(',').append(w.remaining)
                append(',').append(w.currency).append(',').append(w.metric).append(',')
                append(w.showMeter).append(',').append(w.additional).append(',')
                append(w.boundaryKind).append(',').append(w.resetsAtRaw)
            }
        }
        devices.forEach { d ->
            append('#').append(d.deviceId).append(',').append(d.stale).append(',').append(d.updatedAt)
        }
    }
}

/** Parsed `GET /api/health`. Absent `hubBuild` means a legacy Hub, which is fine. */
data class HubHealth(
    val ok: Boolean,
    val role: String,
    val runtime: String,
    val version: Int?,
    val deviceCount: Int,
    val secretRequired: Boolean,
    val hubBuild: HubBuild?
)

/** Content-derived deployment identity (`schemaVersion`, `coreBuildId`, …). */
data class HubBuild(
    val schemaVersion: Int?,
    val runtime: String,
    val coreBuildId: String,
    val runtimeBuildId: String
)
