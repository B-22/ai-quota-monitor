package com.tokenmon.mobile.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tokenmon.mobile.data.model.WidgetSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.widgetStore: DataStore<Preferences> by preferencesDataStore(name = "widget_cache")

/**
 * The last good dashboard snapshot, for the Home Screen widget.
 *
 * A widget is a launcher-rendered view, not a service: it gets a short window at
 * update time and is killed right after. Making it fetch would be unreliable by
 * construction, so the app writes what it fetched and the widget only renders.
 *
 * Only non-secret data lives here — the Hub URL and provider quota figures, in
 * the app's private DataStore. The shared secret is in [com.tokenmon.mobile.data.SecretStore].
 */
class WidgetCache(context: Context) {

    private val store = context.applicationContext.widgetStore

    val snapshot: Flow<WidgetSnapshot> = store.data.map { prefs ->
        val raw = prefs[KEY_SNAPSHOT]
        if (raw.isNullOrBlank()) {
            WidgetSnapshot.EMPTY
        } else {
            WidgetSnapshot.decode(raw) ?: WidgetSnapshot.EMPTY
        }
    }

    suspend fun read(): WidgetSnapshot = snapshot.first()

    suspend fun write(snapshot: WidgetSnapshot) {
        store.edit { prefs ->
            prefs[KEY_SNAPSHOT] = snapshot.encode()
            prefs[KEY_HUB_URL] = snapshot.hubUrl
            prefs[KEY_CAPTURED_AT] = snapshot.capturedAt.toString()
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_SNAPSHOT = stringPreferencesKey("snapshot_json")
        val KEY_HUB_URL = stringPreferencesKey("hub_url")
        val KEY_CAPTURED_AT = stringPreferencesKey("captured_at")
    }
}
