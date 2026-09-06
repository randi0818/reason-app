package me.excuse.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.excuse.app.BuildConfig
import me.excuse.app.appServices
import me.excuse.app.data.buildUsageExportJson

class SettingsViewModel(
    private val exportWriter: suspend (String) -> Int,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = scope ?: viewModelScope
    private var exportJob: Job? = null
    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus = _exportStatus.asStateFlow()
    private val _isExporting = MutableStateFlow(false)
    val isExporting = _isExporting.asStateFlow()

    /** 页面切 tab 或 Activity 重建不会清掉 Activity 级 ViewModel，因此正在写的导出可以继续完成。 */
    fun exportTo(destination: String) {
        if (exportJob?.isActive == true) return
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

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(
                exportWriter = { destination ->
                    writeUsageExport(appContext, Uri.parse(destination))
                },
            ) as T
    }
}

/** 读库、序列化和写文件都在 IO 上；"wt" 明确清空用户选中的已有文档再写入。 */
internal suspend fun writeUsageExport(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
    val snapshot = context.appServices.repository.usageExportSnapshot()
    val zone = ZoneId.systemDefault()
    val json = buildUsageExportJson(
        exportedAtMillis = System.currentTimeMillis(),
        appVersion = BuildConfig.VERSION_NAME,
        timeZoneId = zone.id,
        sessions = snapshot.sessions,
        events = snapshot.events,
        monitoredApps = snapshot.monitoredApps,
        categoryOverrides = snapshot.categoryOverrides,
        toIso = { millis ->
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).toString()
        },
    )

    val stream = context.contentResolver.openOutputStream(uri, "wt")
        ?: error("无法写入所选位置")
    stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

    // 只报“记录”的条数；名单和分类属于配置，混进同一个数字会让结果难以解释。
    snapshot.sessions.size + snapshot.events.size
}
