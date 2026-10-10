package com.tokenmon.mobile.alerts

import com.tokenmon.mobile.data.model.HubStats
import java.time.Instant

/** A notification-worthy condition, evaluated on already-fetched data. */
sealed interface QuotaAlert {
    data class LowQuota(
        val providerName: String,
        val windowLabel: String,
        val remainingPercent: Double
    ) : QuotaAlert

    data class ResetDue(
        val providerName: String,
        val windowLabel: String,
        val resetsAt: Instant
    ) : QuotaAlert

    data class HubOffline(val lastSeen: Instant?) : QuotaAlert
}

/**
 * The notification channel that does not exist yet.
 *
 * Declaring the seam now keeps the *evaluation* logic — the part genuinely worth
 * testing — separate from Android's notification plumbing. Nothing in the current
 * build posts a notification and no background worker polls for these; wiring it
 * up later means supplying a real sink, not restructuring the data path.
 */
interface QuotaAlertSink {
    fun onAlerts(alerts: List<QuotaAlert>)
}

/** The default: evaluation runs, delivery does not. */
object NoopAlertSink : QuotaAlertSink {
    override fun onAlerts(alerts: List<QuotaAlert>) = Unit
}

/**
 * Threshold logic for the three notifications named as future work.
 *
 * Free of Android types so it is unit-testable on the JVM, and so a future
 * WorkManager job becomes a caller rather than a rewrite.
 */
class QuotaAlertEvaluator(
    private val lowThresholdPercent: Double = 20.0,
    /** Bracket of the Hub's own 30s reset-boundary delay; comfortably human-sized. */
    private val resetImminentMs: Long = 10 * 60 * 1000L,
    private val now: () -> Instant = Instant::now
) {

    fun evaluate(stats: HubStats): List<QuotaAlert> {
        val current = now()
        val alerts = mutableListOf<QuotaAlert>()

        for (provider in stats.providers) {
            // A stale or unreadable row says nothing trustworthy about a quota.
            if (provider.parseIssue != null || provider.stale) continue

            for (window in provider.windows) {
                // `additional` buckets are separately metered and `showMeter:
                // false` means the Hub said there is no denominator; neither is
                // a percentage the user would act on.
                if (window.additional || !window.showMeter) continue

                window.remainingPercent
                    ?.takeIf { it <= lowThresholdPercent }
                    ?.let { remaining ->
                        alerts += QuotaAlert.LowQuota(
                            providerName = provider.displayName,
                            windowLabel = window.label,
                            remainingPercent = remaining
                        )
                    }

                val resetsAt = window.resetsAt
                if (resetsAt != null) {
                    val delta = resetsAt.toEpochMilli() - current.toEpochMilli()
                    if (delta in 0..resetImminentMs) {
                        alerts += QuotaAlert.ResetDue(
                            providerName = provider.displayName,
                            windowLabel = window.label,
                            resetsAt = resetsAt
                        )
                    }
                }
            }
        }
        return alerts
    }

    /** The third future notification: the Hub stopped answering. */
    fun hubOffline(lastSeen: Instant?): QuotaAlert = QuotaAlert.HubOffline(lastSeen)
}
