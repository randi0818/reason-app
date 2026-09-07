package me.excuse.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "prefs")

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

class Prefs(private val context: Context) {
    private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

    // 首屏的去向和配色必须来自同一次读取，不能把真实标志与默认浅色拼成一帧。
    val startupPreferences: Flow<StartupPreferences> = context.dataStore.data.map {
        StartupPreferences(
            onboarded = it[KEY_ONBOARDED] ?: false,
            themeMode = ThemeMode.fromKey(it[KEY_THEME_MODE]),
        )
    }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDED] = value }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { ThemeMode.fromKey(it[KEY_THEME_MODE]) }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.key }
    }
}
