package me.excuse.app.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.time.LocalDate
import java.time.ZoneId
import me.excuse.app.data.FakeUsageRepository
import me.excuse.app.data.HistoryRecordCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsViewModelTest {
    private class HistoryRepository : FakeUsageRepository() {
        var counts = HistoryRecordCounts(3, 5)
        var reads = 0
        var deletes = 0
        var cutoff: Long? = null
        var deleteError: Exception? = null
        var releaseDelete: CompletableDeferred<Unit>? = null

        override suspend fun historyCountsBefore(beforeMillis: Long): HistoryRecordCounts {
            reads++
            cutoff = beforeMillis
            return counts
        }

        override suspend fun clearHistoryBefore(beforeMillis: Long, expected: HistoryRecordCounts): HistoryRecordCounts? {
            deletes++
            cutoff = beforeMillis
            releaseDelete?.await()
            deleteError?.let { throw it }
            if (counts != expected) return null
            return counts.also { counts = HistoryRecordCounts(0, 0) }
        }
    }

    private fun withHistoryTest(block: (SettingsViewModel, HistoryRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = HistoryRepository()
        val viewModel = SettingsViewModel(
            repository = repository,
            exportWriter = { 0 },
            scope = scope,
            today = { LocalDate.of(2026, 9, 7) },
            startOfDay = { it.atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli() },
        )
        try { block(viewModel, repository) } finally { scope.cancel() }
    }

    @Test
    fun previewUsesLocalMidnightAndCancelNeverDeletes() = withHistoryTest { vm, repo ->
        val date = LocalDate.of(2026, 3, 8)
        vm.selectCleanupDate(date)
        vm.previewCleanup()
        assertEquals(1772946000000L, repo.cutoff)
        assertEquals(date, vm.cleanupPreview.value?.date)
        assertEquals(HistoryRecordCounts(3, 5), vm.cleanupPreview.value?.counts)
        assertEquals(0, repo.deletes)
        vm.dismissCleanupPreview()
        vm.confirmCleanup()
        assertNull(vm.cleanupPreview.value)
        assertEquals(0, repo.deletes)
    }

    @Test
    fun selectingAnotherDateInvalidatesConfirmationAndRejectsFutureDates() = withHistoryTest { vm, repo ->
        vm.previewCleanup()
        val date = LocalDate.of(2026, 8, 1)
        vm.selectCleanupDate(date)
        vm.confirmCleanup()
        assertEquals(0, repo.deletes)
        vm.selectCleanupDate(LocalDate.of(2026, 9, 8))
        assertEquals(date, vm.cleanupDate.value)
    }

    @Test
    fun emptyHistoryHasNoDestructiveConfirmation() = withHistoryTest { vm, repo ->
        repo.counts = HistoryRecordCounts(0, 0)
        vm.previewCleanup()
        assertNull(vm.cleanupPreview.value)
        assertEquals("所选日期之前没有可清理的记录", vm.cleanupStatus.value)
        vm.confirmCleanup()
        assertEquals(0, repo.deletes)
    }

    @Test
    fun standaloneEventsCanBeCleanedWithoutAnySessions() = withHistoryTest { vm, repo ->
        repo.counts = HistoryRecordCounts(0, 2)
        vm.previewCleanup()
        assertEquals(HistoryRecordCounts(0, 2), vm.cleanupPreview.value?.counts)
        vm.confirmCleanup()
        assertEquals("已清理 0 条使用记录、2 条弹窗结果", vm.cleanupStatus.value)
    }

    @Test
    fun changedCountsRequireANewConfirmation() = withHistoryTest { vm, repo ->
        vm.previewCleanup()
        repo.counts = HistoryRecordCounts(4, 6)
        vm.confirmCleanup()
        assertEquals(HistoryRecordCounts(4, 6), vm.cleanupPreview.value?.counts)
        assertEquals("记录数量发生变化，请核对后再次确认", vm.cleanupStatus.value)
        assertEquals(1, repo.deletes)
        assertEquals(HistoryRecordCounts(4, 6), repo.counts)
        vm.confirmCleanup()
        assertEquals(2, repo.deletes)
        assertEquals("已清理 4 条使用记录、6 条弹窗结果", vm.cleanupStatus.value)
        assertFalse(vm.isManagingHistory.value)
    }

    @Test
    fun cleanupFailureDoesNotReportSuccessOrRetainConfirmation() = withHistoryTest { vm, repo ->
        repo.deleteError = IllegalStateException("disk error")
        vm.previewCleanup()
        vm.confirmCleanup()
        assertEquals("清理失败：disk error", vm.cleanupStatus.value)
        assertNull(vm.cleanupPreview.value)
        assertFalse(vm.isManagingHistory.value)
        assertEquals(HistoryRecordCounts(3, 5), repo.counts)
    }

    @Test
    fun cleanupAndExportCannotStartTwiceOrOverlap() = withHistoryTest { vm, repo ->
        repo.releaseDelete = CompletableDeferred()
        vm.previewCleanup()
        vm.confirmCleanup()
        vm.confirmCleanup()
        vm.previewCleanup()
        vm.exportTo("content://documents/export.json")
        assertEquals(1, repo.reads)
        assertEquals(1, repo.deletes)
        assertTrue(vm.isManagingHistory.value)
        assertFalse(vm.isExporting.value)
        assertNull(vm.exportStatus.value)
    }

    @Test
    fun exportRunsInViewModelOwnedScopeAndReportsCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseWriter = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<String>()
        val viewModel = SettingsViewModel(
            repository = FakeUsageRepository(),
            exportWriter = { destination ->
                writerStarted.complete(destination)
                releaseWriter.await()
                7
            },
            scope = scope,
        )
        try {
            viewModel.exportTo("content://documents/export.json")
            assertEquals("content://documents/export.json", writerStarted.await())
            assertTrue(viewModel.isExporting.value)
            assertEquals("导出中…", viewModel.exportStatus.value)

            releaseWriter.complete(Unit)
            yield()
            assertFalse(viewModel.isExporting.value)
            assertEquals("已导出 7 条记录，含名单和分类", viewModel.exportStatus.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun exportFailureBecomesVisibleStatus() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = SettingsViewModel(
            repository = FakeUsageRepository(),
            exportWriter = { error("disk full") },
            scope = scope,
        )
        try {
            viewModel.exportTo("content://documents/export.json")
            assertFalse(viewModel.isExporting.value)
            assertEquals("导出失败：disk full", viewModel.exportStatus.value)
        } finally {
            scope.cancel()
        }
    }
}
