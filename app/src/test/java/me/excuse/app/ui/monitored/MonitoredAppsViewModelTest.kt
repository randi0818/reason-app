package me.excuse.app.ui.monitored

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.excuse.app.data.FakeUsageRepository
import me.excuse.app.util.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MonitoredAppsViewModelTest {
    @Test
    fun explicitRefreshReplacesInstalledAppSnapshot() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var snapshot = listOf(installedApp("one.app", "One"))
        var loadCalls = 0
        val viewModel = MonitoredAppsViewModel(
            repo = FakeUsageRepository(),
            loadInstalledApps = {
                loadCalls++
                snapshot
            },
            scope = scope,
            installedAppsDispatcher = Dispatchers.Unconfined,
        )
        val collector = scope.launch { viewModel.classifiedApps.collect { } }
        try {
            viewModel.refreshInstalledApps()
            assertEquals(listOf("one.app"), viewModel.classifiedApps.value.map { it.app.packageName })

            snapshot = listOf(installedApp("two.app", "Two"))
            viewModel.refreshInstalledApps()
            assertEquals(listOf("two.app"), viewModel.classifiedApps.value.map { it.app.packageName })
            assertEquals(2, loadCalls)
            assertFalse(viewModel.isLoading.value)
        } finally {
            collector.cancel()
            scope.cancel()
        }
    }

    private fun installedApp(packageName: String, label: String) = InstalledApp(
        packageName = packageName,
        label = label,
        isSystem = false,
        systemCategory = null,
    )
}
