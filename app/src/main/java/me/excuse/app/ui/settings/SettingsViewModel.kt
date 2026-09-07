package me.excuse.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.excuse.app.BuildConfig
import me.excuse.app.appServices
import me.excuse.app.data.HistoryRecordCounts
import me.excuse.app.data.UsageExportJsonWriter
import me.excuse.app.data.UsageRepositoryContract
import me.excuse.app.util.TimeUtil

data class HistoryCleanupPreview(
    val date: LocalDate,
    val beforeMillis: Long,
    val counts: HistoryRecordCounts,
)

class SettingsViewModel(
    private val repository: UsageRepositoryContract,
    private val exportWriter: suspend (String) -> Int,
    scope: CoroutineScope? = null,
    private val today: () -> LocalDate = TimeUtil::today,
    private val startOfDay: (LocalDate) -> Long = TimeUtil::startOfDayMillis,
) : ViewModel() {
    private val workScope = scope ?: viewModelScope
    private var exportJob: Job? = null
    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus = _exportStatus.asStateFlow()
    private val _isExporting = MutableStateFlow(false)
    val isExporting = _isExporting.asStateFlow()
    private val _cleanupDate = MutableStateFlow(today().minusMonths(3))
    val cleanupDate = _cleanupDate.asStateFlow()
    private val _cleanupPreview = MutableStateFlow<HistoryCleanupPreview?>(null)
    val cleanupPreview = _cleanupPreview.asStateFlow()
    private val _cleanupStatus = MutableStateFlow<String?>(null)
    val cleanupStatus = _cleanupStatus.asStateFlow()
    private val _isManagingHistory = MutableStateFlow(false)
    val isManagingHistory = _isManagingHistory.asStateFlow()

    /** 页面切 tab 或 Activity 重建不会清掉 Activity 级 ViewModel，因此正在写的导出可以继续完成。 */
    fun exportTo(destination: String) {
        if (exportJob?.isActive == true || _isManagingHistory.value || _cleanupPreview.value != null) return
        _isExporting.value = true
        _exportStatus.value = "导出中…"
        exportJob = workScope.launch {
            try {
                val written = exportWriter(destination)
                _exportStatus.value = "已导出 $written 条记录，含名单和分类"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _exportStatus.value = "导出失败：" + (error.message ?: error.javaClass.simpleName)
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun clearExportStatus() {
        if (!_isExporting.value) _exportStatus.value = null
    }

    fun selectCleanupDate(date: LocalDate) {
        if (_isExporting.value || _isManagingHistory.value || date > today()) return
        _cleanupDate.value = date
        _cleanupPreview.value = null
        _cleanupStatus.value = null
    }

    fun previewCleanup() {
        if (_isExporting.value || _isManagingHistory.value) return
        val date = _cleanupDate.value
        if (date > today()) {
            _cleanupStatus.value = "只能清理今天之前的记录，请重新选择日期"
            return
        }
        val beforeMillis = startOfDay(date)
        _isManagingHistory.value = true
        _cleanupPreview.value = null
        _cleanupStatus.value = "正在统计…"
        workScope.launch {
            try {
                showCleanupPreview(date, beforeMillis)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _cleanupStatus.value = "读取失败：" + (error.message ?: error.javaClass.simpleName)
            } finally {
                _isManagingHistory.value = false
            }
        }
    }

    fun dismissCleanupPreview() {
        if (!_isManagingHistory.value) _cleanupPreview.value = null
    }

    fun confirmCleanup() {
        if (_isExporting.value || _isManagingHistory.value) return
        val preview = _cleanupPreview.value ?: return
        _isManagingHistory.value = true
        _cleanupPreview.value = null
        _cleanupStatus.value = "正在清理…"
        workScope.launch {
            try {
                val deleted = repository.clearHistoryBefore(preview.beforeMillis, preview.counts)
                if (deleted == null) {
                    showCleanupPreview(preview.date, preview.beforeMillis)
                    if (_cleanupPreview.value != null) {
                        _cleanupStatus.value = "记录数量发生变化，请核对后再次确认"
                    }
                } else {
                    _cleanupStatus.value = "已清理 ${deleted.sessions} 条使用记录、${deleted.events} 条弹窗结果"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _cleanupStatus.value = "清理失败：" + (error.message ?: error.javaClass.simpleName)
            } finally {
                _isManagingHistory.value = false
            }
        }
    }

    private suspend fun showCleanupPreview(date: LocalDate, beforeMillis: Long) {
        val counts = repository.historyCountsBefore(beforeMillis)
        _cleanupPreview.value = if (counts.isEmpty) null else HistoryCleanupPreview(date, beforeMillis, counts)
        _cleanupStatus.value = if (counts.isEmpty) "所选日期之前没有可清理的记录" else null
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(
                repository = appContext.appServices.repository,
                exportWriter = { destination ->
                    writeUsageExport(appContext, Uri.parse(destination))
                },
            ) as T
    }
}

/** 读库、序列化和写文件都在 IO 上；"wt" 明确清空用户选中的已有文档再写入。 */
internal suspend fun writeUsageExport(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
    val zone = ZoneId.systemDefault()
    val temporary = File.createTempFile("usage-export-", ".json", context.cacheDir)
    try {
        // 保持四表快照一致，但不把外部文档提供方的慢写入放在 Room 事务里。
        val count = temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
            context.appServices.repository.exportUsage(
                UsageExportJsonWriter(
                    output = writer,
                    exportedAtMillis = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    timeZoneId = zone.id,
                    toIso = { millis -> OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).toString() },
                )
            )
        }
        currentCoroutineContext().ensureActive()
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("无法写入所选位置")
        stream.use { output ->
            temporary.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    output.write(buffer, 0, size)
                }
            }
        }
        count
    } finally {
        temporary.delete()
    }
}
