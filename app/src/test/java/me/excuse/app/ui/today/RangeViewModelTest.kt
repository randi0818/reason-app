package me.excuse.app.ui.today

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.excuse.app.data.FakeUsageRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class RangeViewModelTest {
    @Test
    fun changingDateRecreatesEveryRollingQueryOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = CountingRangeRepository()
        val initialDate = LocalDate.of(2026, 9, 3)
        val viewModel = RangeViewModel(repository, days = 30, scope = scope, initialDate = initialDate)
        val collectors = listOf(
            scope.launch { viewModel.sessions.collect { } },
            scope.launch { viewModel.entryCount.collect { } },
            scope.launch { viewModel.overrunCount.collect { } },
            scope.launch { viewModel.reasonWall.collect { } },
        )
        try {
            yield()
            assertEquals(listOf(1, 1, 1, 1), repository.calls())

            viewModel.refreshForDate(initialDate.plusDays(1))
            yield()
            assertEquals(listOf(2, 2, 2, 2), repository.calls())

            viewModel.refreshForDate(initialDate.plusDays(1))
            yield()
            assertEquals(listOf(2, 2, 2, 2), repository.calls())
        } finally {
            collectors.forEach { it.cancel() }
            scope.cancel()
        }
    }

    private class CountingRangeRepository : FakeUsageRepository() {
        private var sessionCalls = 0
        private var entryCalls = 0
        private var overrunCalls = 0
        private var wallCalls = 0

        override fun sessionsForRollingDays(n: Int) = flowOf(emptyList<me.excuse.app.data.db.UsageSession>())
            .also { sessionCalls++ }

        override fun entryCountRollingDays(n: Int) = flowOf(0).also { entryCalls++ }

        override fun overrunCountRollingDays(n: Int) = flowOf(0).also { overrunCalls++ }

        override fun reasonWallForRollingDays(n: Int) =
            flowOf(emptyList<me.excuse.app.data.ReasonWallItem>()).also { wallCalls++ }

        fun calls() = listOf(sessionCalls, entryCalls, overrunCalls, wallCalls)
    }
}
