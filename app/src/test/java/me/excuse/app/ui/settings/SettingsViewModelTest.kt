package me.excuse.app.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun exportRunsInViewModelOwnedScopeAndReportsCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseWriter = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<String>()
        val viewModel = SettingsViewModel(
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
