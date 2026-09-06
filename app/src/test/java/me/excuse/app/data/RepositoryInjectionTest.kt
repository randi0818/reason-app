package me.excuse.app.data

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.excuse.app.ui.monitored.MonitoredAppsViewModel
import me.excuse.app.ui.today.DayViewModel
import me.excuse.app.ui.today.RangeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RepositoryInjectionTest {
    @Test
    fun fakeRepositoryConstructsSessionViewModelsWithoutApplication() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeUsageRepository()
        try {
            val day = DayViewModel(repository, LocalDate.of(2026, 9, 1), scope)
            val range = RangeViewModel(repository, days = 30, scope = scope)
            val monitored = MonitoredAppsViewModel(
                repo = repository,
                loadInstalledApps = { emptyList() },
                scope = scope,
            )
            assertEquals(LocalDate.of(2026, 9, 1), day.date)
            assertEquals(0, range.entryCount.value)
            assertNotNull(day.sessions)
            assertNotNull(monitored.classifiedApps)
        } finally {
            scope.cancel()
        }
    }
}
