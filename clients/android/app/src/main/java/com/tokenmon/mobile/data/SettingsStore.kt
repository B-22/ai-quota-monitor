package com.tokenmon.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tokenmon.mobile.data.model.WidgetAppearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.preferencesStore: DataStore<Preferences> by preferencesDataStore(name = "hub_settings")

/** Non-secret connection settings. The secret never passes through here. */
data class HubConfig(
    val hubUrl: String = "",
    val hasSecret: Boolean = false,
    val autoRefresh: Boolean = true,
    /** Home Screen widget background; see [WidgetAppearance] for why it is a setting. */
    val widgetAppearance: WidgetAppearance = WidgetAppearance.SYSTEM
) {
    val isConfigured: Boolean get() = hubUrl.isNotBlank() && hasSecret
}

/**
 * Preferences-backed settings for everything that is not a credential.
 *
 * The Hub URL is not a secret; the shared secret is, and it lives in
 * [SecretStore] inside Keystore-backed encrypted preferences. Keeping the two
 * apart means a bug in this class can never leak the secret into a plain-text
 * preferences file.
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.preferencesStore

    val config: Flow<HubConfig> = store.data.map { prefs ->
        HubConfig(
            hubUrl = prefs[KEY_HUB_URL].orEmpty(),
            hasSecret = prefs[KEY_HAS_SECRET] ?: false,
            autoRefresh = prefs[KEY_AUTO_REFRESH] ?: true,
            widgetAppearance = WidgetAppearance.fromKey(prefs[KEY_WIDGET_APPEARANCE])
        )
    }

    /** The widget reads this outside the app UI, on its own short update window. */
    suspend fun widgetAppearance(): WidgetAppearance = config.first().widgetAppearance

    suspend fun setHubUrl(url: String) {
        store.edit { it[KEY_HUB_URL] = url.trim() }
    }

    suspend fun setHasSecret(has: Boolean) {
        store.edit { prefs ->
            if (has) prefs[KEY_HAS_SECRET] = true else prefs.remove(KEY_HAS_SECRET)
        }
    }

    suspend fun setAutoRefresh(enabled: Boolean) {
        store.edit { it[KEY_AUTO_REFRESH] = enabled }
    }

    suspend fun setWidgetAppearance(appearance: WidgetAppearance) {
        store.edit { it[KEY_WIDGET_APPEARANCE] = appearance.key }
    }

    private companion object {
        val KEY_HUB_URL = stringPreferencesKey("hub_url")
        val KEY_HAS_SECRET = booleanPreferencesKey("has_secret")
        val KEY_AUTO_REFRESH = booleanPreferencesKey("auto_refresh")
        val KEY_WIDGET_APPEARANCE = stringPreferencesKey("widget_appearance")
    }
}
