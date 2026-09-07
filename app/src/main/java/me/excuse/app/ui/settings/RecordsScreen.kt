package me.excuse.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.Paper
import me.excuse.app.ui.theme.SurfaceBg

@Composable
fun RecordsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(LocalContext.current))
    val exportStatus by vm.exportStatus.collectAsStateWithLifecycle()
    val isExporting by vm.isExporting.collectAsStateWithLifecycle()
    val cleanupDate by vm.cleanupDate.collectAsStateWithLifecycle()
    val preview by vm.cleanupPreview.collectAsStateWithLifecycle()
    val cleanupStatus by vm.cleanupStatus.collectAsStateWithLifecycle()
    val isManagingHistory by vm.isManagingHistory.collectAsStateWithLifecycle()
    val busy = isExporting || isManagingHistory
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportTo(uri.toString()) }

    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("← 返回", color = Muted)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp).padding(top = 8.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Text("管理记录", fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Light, color = Ink)
                Note("仅存本机，不会自动清理。")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("导出", fontSize = 24.sp, fontWeight = FontWeight.Light, color = Ink)
                    Note("导出全部记录、名单和分类为 JSON，暂不支持导入。")
                    Button(
                        onClick = {
                            vm.clearExportStatus()
                            exportLauncher.launch("reason-${LocalDate.now()}.json")
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
                    ) { Text(if (isExporting) "正在导出…" else "导出全部记录") }
                    exportStatus?.let { Note(it) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("清理", fontSize = 24.sp, fontWeight = FontWeight.Light, color = Ink)
                    Note("仅清理所选日期前已结束的记录，名单和分类不受影响。")
                    Text("清理此日期之前 · 不含当天", fontSize = 12.sp, color = Muted)
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, Muted),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    ) { Text(cleanupDate.toString(), fontSize = 24.sp, fontWeight = FontWeight.Light) }
                    Button(
                        onClick = vm::previewCleanup,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceBg, contentColor = Ink),
                    ) { Text(if (isManagingHistory) "正在处理…" else "预览清理范围") }
                    cleanupStatus?.let { Note(it) }
                    Note("清理不可恢复，相关统计会减少。建议先导出留存。")
                }
            }
        }
    }
    if (showDatePicker) {
        CleanupDatePicker(
            selected = cleanupDate,
            onDismiss = { showDatePicker = false },
            onSelected = { vm.selectCleanupDate(it); showDatePicker = false },
        )
    }
    preview?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissCleanupPreview,
            shape = RectangleShape,
            containerColor = Paper,
            titleContentColor = Ink,
            textContentColor = Ink,
            title = { Text("确认清理") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${pending.date} 之前（不含当天）")
                    Text("${pending.counts.sessions} 条使用记录\n${pending.counts.events} 条弹窗结果", fontSize = 20.sp)
                    Text("理由内容会永久删除，相关统计会减少。导出的 JSON 暂不支持导入恢复。")
                    cleanupStatus?.let { Text(it) }
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissCleanupPreview) { Text("取消", color = Ink) } },
            confirmButton = { TextButton(onClick = vm::confirmCleanup) { Text("永久清理", color = Ink) } },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 21.sp, color = Muted)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanupDatePicker(selected: LocalDate, onDismiss: () -> Unit, onSelected: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val dialogHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(1).dp
    // Material DatePicker 用 UTC 表示日历日期；真正的清理边界由 ViewModel 换算成本地零点。
    val selectableDates = remember(today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                Instant.ofEpochMilli(utcTimeMillis).atOffset(ZoneOffset.UTC).toLocalDate() <= today
            override fun isSelectableYear(year: Int) = year <= today.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 1970..today.year,
        selectableDates = selectableDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = dialogHeight),
        shape = RectangleShape,
        colors = DatePickerDefaults.colors(containerColor = Paper),
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onSelected(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()) }
                },
            ) { Text("选择", color = Ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Ink) } },
    ) {
        DatePicker(
            state = state,
            // 横屏 / 大字体时只滚动日历内容，给底部确认和取消保留空间。
            modifier = Modifier.verticalScroll(rememberScrollState()),
            colors = DatePickerDefaults.colors(
                containerColor = Paper,
                titleContentColor = Muted,
                headlineContentColor = Ink,
                weekdayContentColor = Muted,
                subheadContentColor = Ink,
                yearContentColor = Ink,
                currentYearContentColor = Ink,
                selectedYearContentColor = Paper,
                selectedYearContainerColor = Ink,
                dayContentColor = Ink,
                selectedDayContentColor = Paper,
                selectedDayContainerColor = Ink,
                todayContentColor = Ink,
                todayDateBorderColor = Ink,
            ),
        )
    }
}
