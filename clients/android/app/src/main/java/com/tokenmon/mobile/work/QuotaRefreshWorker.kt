package com.tokenmon.mobile.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tokenmon.mobile.TokenMonitorApp
import com.tokenmon.mobile.data.HubRequestException
import com.tokenmon.mobile.data.WidgetSnapshotPolicy
import com.tokenmon.mobile.data.WidgetSnapshotRequest
import com.tokenmon.mobile.data.model.WidgetSnapshot
import com.tokenmon.mobile.widget.updateQuotaWidgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Keeps the Home Screen widget fresh while the app is closed.
 *
 * This is the whole background story, and it is a `WorkManager` periodic job —
 * not a foreground service, not a resident process, and not a Hub-side cron. The
 * requirement was "定期刷新即可", and WorkManager's 15-minute floor is the
 * platform's own answer to that.
 *
 * It performs one authenticated `GET /api/stats` and writes the widget cache. It
 * does not open the SSE stream: holding a long-lived connection from a background
 * job would require exactly the kind of always-on service this app refuses to run.
 */
class QuotaRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TokenMonitorApp).container
        return try {
            val request = container.connectionMutex.withLock {
                val config = container.settingsStore.config.first()
                // Clear a persisted snapshot from another Hub even when this
                // fetch fails or the current configuration has been cleared.
                val cached = container.widgetCache.read()
                if (!WidgetSnapshotPolicy.cacheMatches(config, cached.hubUrl)) {
                    container.widgetCache.clear()
                    updateQuotaWidgets(applicationContext)
                }
                WidgetSnapshotRequest(config, container.connectionRevision)
            }
            val config = request.config
            if (!config.isConfigured) return Result.success()
            val base = container.hubClient.baseUrl(config.hubUrl) ?: return Result.success()

            // The network call must not hold the lock needed by URL/secret changes.
            val stats = container.hubClient.stats(base)
            val snapshot = WidgetSnapshot(
                capturedAt = stats.updatedAt ?: java.time.Instant.now(),
                hubUrl = config.hubUrl,
                providers = stats.providers
            )
            container.connectionMutex.withLock {
                val currentConfig = container.settingsStore.config.first()
                if (WidgetSnapshotPolicy.canPublish(request, currentConfig, container.connectionRevision, snapshot.hubUrl)) {
                    // Verification, cache write and redraw are one critical
                    // section: a configuration switch cannot pass between them.
                    container.widgetCache.write(snapshot)
                    updateQuotaWidgets(applicationContext)
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HubRequestException) {
            // A rejected secret will not fix itself; retrying just burns battery.
            // An unconfigured Hub is the user's to fix, not WorkManager's.
            if (error.httpCode == 401 || error.httpCode == 403) Result.success() else Result.retry()
        } catch (error: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "token-monitor-widget-refresh"

        /** Android's own floor for periodic work. */
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<QuotaRefreshWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
                1, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
