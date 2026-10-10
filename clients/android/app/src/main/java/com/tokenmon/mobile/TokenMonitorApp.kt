package com.tokenmon.mobile

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.tokenmon.mobile.alerts.NoopAlertSink
import com.tokenmon.mobile.alerts.QuotaAlertSink
import com.tokenmon.mobile.data.HubClient
import com.tokenmon.mobile.data.HubStream
import com.tokenmon.mobile.data.SecretState
import com.tokenmon.mobile.data.SecretStore
import com.tokenmon.mobile.data.SettingsStore
import com.tokenmon.mobile.widget.WidgetCache
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

/**
 * Manual dependency wiring.
 *
 * The graph is a handful of singletons, so a DI framework would add a compiler
 * plugin and an annotation processor to save nothing. Plain construction keeps
 * the whole dependency list readable in one screen.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val secretStore = SecretStore(appContext)
    val widgetCache = WidgetCache(appContext)

    /** Serializes connection changes with background snapshot commits. */
    val connectionMutex = Mutex()

    // Only an in-memory generation, never a credential or its hash. Volatile
    // also permits the foreground feed to reject old updates without locking.
    @Volatile
    var connectionRevision: Long = 0L
        private set

    /** Caller must hold [connectionMutex]. Also detects URL/credential ABA. */
    fun advanceConnectionRevision() {
        connectionRevision++
    }

    /**
     * The current secret, as the synchronous accessor OkHttp needs.
     *
     * [SecretStore] is suspend because it reads DataStore, while an OkHttp
     * interceptor and the SSE request builder both want a plain value on the
     * calling thread. `runBlocking` bridges that here — deliberately at this one
     * seam rather than by making every HTTP entry point suspend. DataStore serves
     * from memory once loaded, and OkHttp calls always arrive on a background
     * thread, so no call blocks the UI.
     */
    private val currentSecret: () -> String = {
        runBlocking { (secretStore.read() as? SecretState.Loaded)?.secret.orEmpty() }
    }

    val hubClient = HubClient(currentSecret)
    val hubStream = HubStream(currentSecret)

    val alertSink: QuotaAlertSink = NoopAlertSink

    val networkMonitor = NetworkMonitor(appContext)
}

/** Observes reachability so the stream can retry the moment the phone is back online. */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun isOnline(): Boolean {
        val manager = connectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Invokes [onAvailable] whenever a network with internet capability appears,
     * and returns the matching unregister function.
     */
    fun onAvailable(onAvailable: () -> Unit): () -> Unit {
        val manager = connectivityManager ?: return {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onAvailable()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    onAvailable()
                }
            }
        }
        val unregister: () -> Unit = {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (ignored: IllegalArgumentException) {
                // Already unregistered, or the process is going away.
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            unregister
        } catch (error: RuntimeException) {
            // No connectivity service, or it refused the callback. The stream
            // still reconnects on its own backoff, so this is not fatal.
            {}
        }
    }
}

class TokenMonitorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
