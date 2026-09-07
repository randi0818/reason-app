package me.excuse.app

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import me.excuse.app.data.UsageRepositoryContract
import me.excuse.app.data.prefs.Prefs
import me.excuse.app.data.prefs.StartupPreferences
import me.excuse.app.data.prefs.ThemeMode
import me.excuse.app.service.SessionCleanupQueue

interface AppServices {
    val repository: UsageRepositoryContract
    val prefs: Prefs
    val themeModeFlow: StateFlow<ThemeMode>
    val startupPreferencesFlow: StateFlow<StartupPreferences?>
    val sessionCleanupQueue: SessionCleanupQueue
}

val Context.appServices: AppServices
    get() = applicationContext as AppServices
