package me.excuse.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import me.excuse.app.data.UsageRepository
import me.excuse.app.data.UsageRepositoryContract
import me.excuse.app.data.db.AppDatabase
import me.excuse.app.data.prefs.Prefs
import me.excuse.app.data.prefs.StartupPreferences
import me.excuse.app.data.prefs.ThemeMode
import me.excuse.app.service.SessionCleanupQueue

class App : Application(), AppServices {
    override lateinit var repository: UsageRepositoryContract
        private set
    override lateinit var prefs: Prefs
        private set
    override lateinit var themeModeFlow: StateFlow<ThemeMode>
        private set
    override lateinit var startupPreferencesFlow: StateFlow<StartupPreferences?>
        private set
    override val sessionCleanupQueue = SessionCleanupQueue(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onFailure = { operation, error -> Log.e("ReasonMonitor", "$operation failed", error) },
    )

    override fun onCreate() {
        super.onCreate()
        repository = UsageRepository(AppDatabase.get(this))
        prefs = Prefs(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        themeModeFlow = prefs.themeMode.stateIn(scope, SharingStarted.Eagerly, ThemeMode.LIGHT)
        startupPreferencesFlow = prefs.startupPreferences.stateIn(scope, SharingStarted.Eagerly, null)
    }
}
