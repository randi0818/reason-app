package me.excuse.app.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
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
    var page by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = page.isNotEmpty()) { page = "" }
    when (page) {
        "guide" -> {
            UsageGuideScreen(onBack = { page = "" })
            return
        }
        "records" -> {
            RecordsScreen(onBack = { page = "" })
            return
        }
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
                onClick = { page = "guide" },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper)
            ) {
                Text("使用说明")
            }

            Button(
                onClick = { page = "records" },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceBg, contentColor = Ink)
            ) {
                Text("管理记录")
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
