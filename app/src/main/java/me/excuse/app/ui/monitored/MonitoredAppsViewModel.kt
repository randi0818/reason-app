package me.excuse.app.ui.monitored

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.excuse.app.category.AppCategory
import me.excuse.app.category.AppCategoryClassifier
import me.excuse.app.category.AppClassification
import me.excuse.app.data.UsageRepositoryContract
import me.excuse.app.util.AppInfoUtil
import me.excuse.app.util.InstalledApp

data class ClassifiedInstalledApp(
    val app: InstalledApp,
    val classification: AppClassification
)

class MonitoredAppsViewModel(
    private val repo: UsageRepositoryContract,
    private val loadInstalledApps: () -> List<InstalledApp>,
    scope: CoroutineScope? = null,
    private val installedAppsDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val workScope = scope ?: viewModelScope
    private val classifier = AppCategoryClassifier()

    private val _installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    private var refreshInProgress = false
    private var refreshQueued = false

    val classifiedApps = combine(
        _installed,
        repo.appCategoryOverrides()
    ) { installed, manualCategories ->
        installed.map { installedApp ->
            ClassifiedInstalledApp(
                app = installedApp,
                classification = classifier.classify(
                    packageName = installedApp.packageName,
                    manualCategory = manualCategories[installedApp.packageName],
                    systemCategory = installedApp.systemCategory,
                    appLabel = installedApp.label,
                )
            )
        }
    }.stateIn(workScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val monitoredPackages = repo.monitoredApps()
        .map { list -> list.filter { it.enabled }.map { it.packageName }.toSet() }
        .stateIn(workScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** 安装列表不是数据库 Flow；回到页面或收到包变更广播时需要重新向 PackageManager 取快照。 */
    fun refreshInstalledApps() {
        if (refreshInProgress) {
            refreshQueued = true
            return
        }
        refreshInProgress = true
        if (_installed.value.isEmpty()) _isLoading.value = true

        workScope.launch {
            try {
                do {
                    refreshQueued = false
                    val refreshed = try {
                        withContext(installedAppsDispatcher) { loadInstalledApps() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (refreshed != null) _installed.value = refreshed
                } while (refreshQueued)
            } finally {
                refreshInProgress = false
                _isLoading.value = false
            }
        }
    }

    fun toggle(item: InstalledApp, enabled: Boolean) {
        workScope.launch(Dispatchers.IO) {
            repo.setMonitored(item.packageName, item.label, enabled)
        }
    }

    fun setCategory(item: InstalledApp, category: AppCategory?) {
        workScope.launch(Dispatchers.IO) {
            repo.setAppCategory(item.packageName, category)
        }
    }

    class Factory(
        private val context: Context,
        private val repository: UsageRepositoryContract,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appContext = context.applicationContext
            return MonitoredAppsViewModel(
                repo = repository,
                loadInstalledApps = { AppInfoUtil.loadInstalledApps(appContext) },
            ) as T
        }
    }
}
