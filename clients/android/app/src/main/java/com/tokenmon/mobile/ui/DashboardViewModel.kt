package com.tokenmon.mobile.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tokenmon.mobile.AppContainer
import com.tokenmon.mobile.TokenMonitorApp
import com.tokenmon.mobile.data.ConnectionStatus
import com.tokenmon.mobile.data.FeedUpdate
import com.tokenmon.mobile.data.HubRepository
import com.tokenmon.mobile.data.HubConfig
import com.tokenmon.mobile.data.HubClient
import com.tokenmon.mobile.data.FeedConnectionPolicy
import com.tokenmon.mobile.data.HubRequestException
import com.tokenmon.mobile.data.ProbeResult
import com.tokenmon.mobile.data.SecretState
import com.tokenmon.mobile.data.WidgetSnapshotPolicy
import com.tokenmon.mobile.data.model.HubStats
import com.tokenmon.mobile.data.model.WidgetAppearance
import com.tokenmon.mobile.data.model.WidgetSnapshot
import com.tokenmon.mobile.widget.updateQuotaWidgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Everything the dashboard and settings screens render from. */
data class UiState(
    val config: HubConfig = HubConfig(),
    val stats: HubStats? = null,
    val status: ConnectionStatus = ConnectionStatus.NotConfigured,
    val statusMessage: String? = null,
    val isRefreshing: Boolean = false,
    val isTesting: Boolean = false,
    val probe: ProbeResult? = null,
    val secretNeedsReentry: Boolean = false,
    val isForeground: Boolean = false
) {
    val hubConfigured: Boolean get() = config.isConfigured

    /** True when there is nothing on screen yet and nothing is loading. */
    val showEmptyState: Boolean
        get() = stats == null && status !in setOf(ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)
}

/**
 * Holds the dashboard state for the process.
 *
 * The feed is started from the Activity's STARTED lifecycle and stopped when it
 * leaves, because SSE is a foreground feature: leaving it open in the background
 * would hold a socket open for a screen nobody is looking at. The periodic
 * WorkManager job covers the widget while the app is closed.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = (application as TokenMonitorApp).container

    private val settingsStore = container.settingsStore
    private val secretStore = container.secretStore
    private val repository: HubRepository = HubRepository(
        client = container.hubClient,
        stream = container.hubStream,
        widgetCache = container.widgetCache,
        networkMonitor = container.networkMonitor,
        onSnapshotWritten = { updateWidgets() }
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var feedJob: Job? = null
    private var refreshJob: Job? = null
    private var probeJob: Job? = null
    private var refreshRevision = 0L
    private val secretRevision: Long get() = container.connectionRevision
    private val connectionMutex = container.connectionMutex
    private val lifecyclePolicy = FeedLifecyclePolicy()
    private var cacheScopeChecked = false
    private var configLoaded = false

    init {
        viewModelScope.launch {
            settingsStore.config.collect {
                connectionMutex.withLock {
                    // An emission may have waited behind a save operation.
                    // Re-read under the lock so it cannot reopen the old Hub.
                    val config = settingsStore.config.first()
                    val previous = _state.value.config
                    if (configLoaded && (previous.hubUrl != config.hubUrl || previous.hasSecret != config.hasSecret)) {
                        container.advanceConnectionRevision()
                    }
                    configLoaded = true
                    _state.update {
                        val changed = it.config.hubUrl != config.hubUrl || it.config.hasSecret != config.hasSecret
                        it.copy(config = config, stats = if (changed) null else it.stats,
                            probe = if (changed) null else it.probe)
                    }
                    reconcileFeedLocked()
                }
            }
        }
        viewModelScope.launch {
            when (secretStore.read()) {
                is SecretState.NeedsReentry ->
                    _state.update { it.copy(secretNeedsReentry = true) }
                else -> Unit
            }
        }
    }

    /** Called from the Activity on STARTED. Idempotent. */
    fun onForeground() {
        _state.update { it.copy(isForeground = true) }
        scheduleReconcile()
    }

    fun onBackground() {
        _state.update { it.copy(isForeground = false, isRefreshing = false) }
        feedJob?.cancel()
        refreshJob?.cancel()
        scheduleReconcile()
    }

    private fun scheduleReconcile() {
        viewModelScope.launch {
            connectionMutex.withLock { reconcileFeedLocked() }
        }
    }

    private fun identity(): FeedIdentity = FeedLifecyclePolicy.identity(_state.value.config, secretRevision)

    /** Must hold connectionMutex: old cache writers finish before cache removal. */
    private suspend fun stopConnectionWork() {
        refreshRevision++
        feedJob?.cancelAndJoin()
        feedJob = null
        refreshJob?.cancelAndJoin()
        refreshJob = null
        probeJob?.cancelAndJoin()
        probeJob = null
        _state.update { it.copy(isRefreshing = false, isTesting = false) }
    }

    private suspend fun reconcileFeedLocked() {
        if (!configLoaded) return
        val current = _state.value
        val plan = lifecyclePolicy.reconcile(
            current.config, current.isForeground, secretRevision, feedJob?.isActive == true
        )
        if (plan.clearData || plan.stop || plan.restart) stopConnectionWork()
        if (plan.clearData) {
            _state.update {
                it.copy(stats = null, probe = null, statusMessage = null,
                    status = if (it.config.isConfigured) ConnectionStatus.Connecting else ConnectionStatus.NotConfigured)
            }
            clearWidgetCache()
        } else if (!cacheScopeChecked) {
            // On process start retain a matching cache, but never render a
            // snapshot from a different Hub or from a cleared configuration.
            val cached = container.widgetCache.read()
            if (!WidgetSnapshotPolicy.cacheMatches(current.config, cached.hubUrl)) clearWidgetCache()
        }
        cacheScopeChecked = true

        if (!current.config.isConfigured) {
            _state.update { it.copy(stats = null, status = ConnectionStatus.NotConfigured, statusMessage = null) }
            return
        }
        if (!plan.restart || !_state.value.isForeground || !_state.value.config.autoRefresh) return
        val base = container.hubClient.baseUrl(current.config.hubUrl)
        if (base == null) {
            _state.update { it.copy(status = ConnectionStatus.Offline, statusMessage = "Hub 地址无效") }
            return
        }
        val scopeIdentity = identity()
        feedJob = viewModelScope.launch {
            repository.observe(base, current.config.hubUrl).collect { update ->
                val state = _state.value
                if (scopeIdentity == identity() && state.isForeground && state.config.autoRefresh) applyFeed(update)
            }
        }
    }

    private fun applyFeed(update: FeedUpdate) {
        _state.update { current ->
            current.copy(
                stats = update.stats ?: current.stats,
                status = update.status,
                statusMessage = update.message
            )
        }
    }

    private suspend fun updateWidgets() {
        try {
            updateQuotaWidgets(getApplication())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("DashboardViewModel", "Widget refresh failed: ${error.javaClass.simpleName}")
        }
    }

    private suspend fun clearWidgetCache() {
        container.widgetCache.clear()
        updateWidgets()
    }

    fun refreshNow() {
        val config = _state.value.config
        if (!config.isConfigured || !_state.value.isForeground) return
        val base = container.hubClient.baseUrl(config.hubUrl) ?: return
        val scopeIdentity = identity()
        val revision = ++refreshRevision
        refreshJob?.cancel()
        _state.update { it.copy(isRefreshing = true) }
        refreshJob = viewModelScope.launch {
            try {
                val stats = container.hubClient.stats(base)
                if (scopeIdentity != identity() || revision != refreshRevision) return@launch
                val status = if (_state.value.status == ConnectionStatus.Live) ConnectionStatus.Live else ConnectionStatus.Polling
                applyFeed(FeedUpdate(stats, status))
                try {
                    container.widgetCache.write(
                        WidgetSnapshot(
                            capturedAt = stats.updatedAt ?: java.time.Instant.now(),
                            hubUrl = config.hubUrl,
                            providers = stats.providers
                        )
                    )
                    updateWidgets()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("DashboardViewModel", "Widget cache failed: ${error.javaClass.simpleName}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (scopeIdentity == identity() && revision == refreshRevision) {
                    val authFailed = error is HubRequestException && FeedConnectionPolicy.isAuthFailure(error.httpCode)
                    _state.update {
                        it.copy(
                            statusMessage = describe(error),
                            status = if (authFailed) ConnectionStatus.AuthFailed else ConnectionStatus.Offline
                        )
                    }
                }
            } finally {
                if (scopeIdentity == identity() && revision == refreshRevision) {
                    _state.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    fun saveHubUrl(url: String) {
        viewModelScope.launch {
            connectionMutex.withLock {
                val trimmed = url.trim()
                val changed = trimmed != _state.value.config.hubUrl
                if (changed) {
                    container.advanceConnectionRevision()
                    stopConnectionWork()
                }
                settingsStore.setHubUrl(trimmed)
                _state.update { it.copy(config = it.config.copy(hubUrl = trimmed),
                    stats = if (changed) null else it.stats, probe = if (changed) null else it.probe) }
                reconcileFeedLocked()
            }
        }
    }

    fun saveSecret(secret: String) {
        viewModelScope.launch {
            connectionMutex.withLock {
                stopConnectionWork()
                val ok = secretStore.write(secret)
                if (ok) container.advanceConnectionRevision()
                val hasSecret = secretStore.hasSecret()
                settingsStore.setHasSecret(hasSecret)
                _state.update {
                    it.copy(config = it.config.copy(hasSecret = hasSecret), secretNeedsReentry = !ok,
                        statusMessage = if (ok) null else "Secret 保存失败（Keystore 不可用）")
                }
                reconcileFeedLocked()
            }
        }
    }

    fun clearSecret() {
        viewModelScope.launch {
            connectionMutex.withLock {
                stopConnectionWork()
                secretStore.clear()
                container.advanceConnectionRevision()
                settingsStore.setHasSecret(false)
                _state.update { it.copy(config = it.config.copy(hasSecret = false), probe = null, secretNeedsReentry = false) }
                reconcileFeedLocked()
            }
        }
    }

    fun setAutoRefresh(enabled: Boolean) {
        viewModelScope.launch {
            connectionMutex.withLock {
                settingsStore.setAutoRefresh(enabled)
                _state.update { it.copy(config = it.config.copy(autoRefresh = enabled)) }
                reconcileFeedLocked()
            }
        }
    }

    /**
     * Appearance only, so no connection state changes here.
     *
     * Still refreshes the widget immediately: the launcher keeps the last
     * RemoteViews until something pushes new ones, so without this the new
     * background would only appear after the next 15-minute job.
     */
    fun setWidgetAppearance(appearance: WidgetAppearance) {
        viewModelScope.launch {
            settingsStore.setWidgetAppearance(appearance)
            _state.update { it.copy(config = it.config.copy(widgetAppearance = appearance)) }
            updateWidgets()
        }
    }

    /**
     * "测试连接" — reports each failure mode distinctly instead of "发生错误".
     *
     * [secret] may be [EXISTING_SECRET], meaning "test with what is already in
     * the keystore", so re-typing a working secret is not required to re-verify
     * a changed URL.
     */
    fun testConnection(url: String, secret: String) {
        _state.update { it.copy(isTesting = true, probe = null) }
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            val effective = if (secret == EXISTING_SECRET) {
                (secretStore.read() as? SecretState.Loaded)?.secret.orEmpty()
            } else {
                secret
            }
            // Probe uses its own client: an unsaved test secret must not
            // override the active feed's REST requests.
            val result = HubClient { "" }.probe(url, effective)
            _state.update { it.copy(isTesting = false, probe = result) }
        }
    }

    fun clearProbe() {
        _state.update { it.copy(probe = null) }
    }

    private fun describe(error: Throwable): String = when (error) {
        is IOException -> "无法连接 Hub，请检查网络与地址"
        is HubRequestException ->
            if (FeedConnectionPolicy.isAuthFailure(error.httpCode)) {
                "Secret 被 Hub 拒绝（HTTP ${error.httpCode}）"
            } else if (error.parseFailure != null) {
                "无法解析 Hub 数据，正在重试"
            } else {
                "Hub 返回 HTTP ${error.httpCode}"
            }
        else -> "无法读取 Hub 数据"
    }
}
