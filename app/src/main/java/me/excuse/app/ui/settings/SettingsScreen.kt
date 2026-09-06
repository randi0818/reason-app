package me.excuse.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDate
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.excuse.app.BuildConfig
import me.excuse.app.appServices
import me.excuse.app.data.prefs.ThemeMode
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.MutedSoft
import me.excuse.app.ui.theme.Paper
import me.excuse.app.ui.theme.SurfaceBg

@Composable
fun SettingsScreen(onRedoPermissions: () -> Unit) {
    val context = LocalContext.current
    var showGuide by remember { mutableStateOf(false) }
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(context))
    val exportStatus by vm.exportStatus.collectAsStateWithLifecycle()
    val isExporting by vm.isExporting.collectAsStateWithLifecycle()

    // SAF：系统的文件选择器负责落盘，app 只拿到一个 Uri 往里写。
    // 不需要任何新权限，也不需要 INTERNET —— 数据的出口是用户，不是这个 app。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            vm.exportTo(uri.toString())
        }
    }

    // 系统返回（导航条 / 全面屏手势）在使用说明页退回设置主页
    BackHandler(enabled = showGuide) {
        showGuide = false
    }

    if (showGuide) {
        UsageGuideScreen(onBack = { showGuide = false })
        return
    }

    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(top = 56.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            // Hub 风大标题
            Text(
                text = "设置",
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                color = Ink,
                lineHeight = 64.sp
            )

            // 版本块：小标签 + 大字号版本号
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "版本",
                    fontSize = 12.sp,
                    color = Muted,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = BuildConfig.VERSION_NAME,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                    color = Ink,
                    lineHeight = 36.sp
                )
                Text(
                    text = "build ${BuildConfig.VERSION_CODE}",
                    fontSize = 12.sp,
                    color = Muted
                )
            }

            // 主题选择块：小标签 + 三个堆叠按钮
            ThemeSection()

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { showGuide = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper)
            ) {
                Text("使用说明")
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        vm.clearExportStatus()
                        exportLauncher.launch("reason-" + LocalDate.now() + ".json")
                    },
                    enabled = !isExporting,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceBg, contentColor = Ink)
                ) {
                    Text("导出记录")
                }
                if (exportStatus != null) {
                    Text(
                        text = exportStatus.orEmpty(),
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }

            Button(
                onClick = onRedoPermissions,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceBg, contentColor = Ink)
            ) {
                Text("权限与自启动设置")
            }
        }
    }
}

@Composable
private fun ThemeSection() {
    val services = LocalContext.current.appServices
    val mode by services.themeModeFlow.collectAsState()
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "主题",
            fontSize = 12.sp,
            color = Muted,
            letterSpacing = 0.5.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                ThemeMode.SYSTEM to "跟随系统",
                ThemeMode.LIGHT to "浅色",
                ThemeMode.DARK to "深色"
            ).forEach { (m, label) ->
                ThemeChip(
                    label = label,
                    selected = m == mode,
                    onClick = { scope.launch { services.prefs.setThemeMode(m) } }
                )
            }
        }
    }
}

@Composable
private fun RowScope.ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(36.dp),
        shape = RoundedCornerShape(0.dp),
        border = BorderStroke(1.dp, if (selected) Ink else MutedSoft),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Ink else SurfaceBg,
            contentColor = if (selected) Paper else Ink
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(label, fontSize = 13.sp)
    }
}
