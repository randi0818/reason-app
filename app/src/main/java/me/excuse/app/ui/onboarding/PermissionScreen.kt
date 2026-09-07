package me.excuse.app.ui.onboarding

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import me.excuse.app.ui.theme.Disabled
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.Paper
import me.excuse.app.ui.theme.SurfaceBg

@Composable
fun PermissionScreen(
    isInitialSetup: Boolean = true,
    onAllGranted: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 显式 state — 比 tick + 重读靠谱
    var usage by remember { mutableStateOf(PermissionUtils.hasUsageAccess(context)) }
    var overlay by remember { mutableStateOf(PermissionUtils.hasOverlay(context)) }
    var notif by remember { mutableStateOf(PermissionUtils.hasNotification(context)) }
    var notificationRequestAttempted by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        usage = PermissionUtils.hasUsageAccess(context)
        overlay = PermissionUtils.hasOverlay(context)
        notif = PermissionUtils.hasNotification(context)
    }

    // 切回 app 时刷新一次（绝大多数情况这里就够了）
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 兜底：800ms 轮询。万一某些 ROM 不发 ON_RESUME 也能跟上
    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            refresh()
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        notificationRequestAttempted = true
        refresh()
    }

    val coreOk = usage && overlay && notif
    val isAggressiveRom = PermissionUtils.isVendorRomLikelyAggressive()

    Surface(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (isInitialSetup) "先开几个权限" else "权限与后台设置",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (isInitialSetup) {
                        "开好必要权限后，可以继续检查自启动或电池设置，再点底部按钮进入主界面。"
                    } else {
                        "检查运行所需权限，并按需调整自启动或电池策略。"
                    },
                    color = Muted,
                )
                Spacer(Modifier.height(8.dp))

                PermissionCard(
                    title = "使用情况访问权限",
                    desc = "用来知道你正在用哪个 app、什么时候切换。看不到屏幕内容、聊天和输入。",
                    granted = usage,
                    onGo = { PermissionUtils.openUsageAccessSettings(context) }
                )
                PermissionCard(
                    title = "悬浮窗权限",
                    desc = "用来盖在被监控 app 上弹问题。这个窗禁止截屏录屏，理由不会被录进去。",
                    granted = overlay,
                    onGo = { PermissionUtils.openOverlaySettings(context) }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val notificationAction = notificationPermissionAction(
                        requestAttempted = notificationRequestAttempted,
                        shouldShowRationale = PermissionUtils
                            .shouldShowNotificationPermissionRationale(context),
                    )
                    PermissionCard(
                        title = "通知权限（Android 13+）",
                        desc = if (notificationAction == NotificationPermissionAction.OPEN_SETTINGS) {
                            "系统已不再弹出权限请求，请在应用通知设置中开启。"
                        } else {
                            "前台服务需要一个常驻通知。它不显示你正在用哪个 app，锁屏上也看不出来。"
                        },
                        granted = notif,
                        actionLabel = when (notificationAction) {
                            NotificationPermissionAction.REQUEST -> if (notificationRequestAttempted) {
                                "再次请求"
                            } else {
                                "去开启"
                            }
                            NotificationPermissionAction.OPEN_SETTINGS -> "打开设置"
                        },
                        onGo = {
                            when (notificationAction) {
                                NotificationPermissionAction.REQUEST -> {
                                    notificationRequestAttempted = true
                                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                                NotificationPermissionAction.OPEN_SETTINGS -> {
                                    PermissionUtils.openNotificationSettings(context)
                                }
                            }
                        }
                    )
                }

                // 原生 Android 通常没有独立的自启动页，仍要明确告诉用户按钮会去哪里。
                val autostartTitle = if (isAggressiveRom) {
                    "自启动 / 后台（强烈建议）"
                } else {
                    "自启动 / 电池策略（可选）"
                }
                val autostartDesc = if (isAggressiveRom) {
                    "打开厂商的自启动或后台管理，把本 app 设为允许或无限制。"
                } else {
                    "这台手机通常没有单独的自启动开关；按钮会打开电池优化设置。"
                }

                PermissionCard(
                    title = autostartTitle,
                    desc = autostartDesc,
                    granted = false,
                    actionLabel = "打开设置",
                    onGo = { PermissionUtils.openAutostartSettings(context) }
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        refresh()
                        if (usage && overlay && notif) onAllGranted()
                    },
                    enabled = coreOk,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ink,
                        contentColor = Paper,
                        disabledContainerColor = Disabled
                    )
                ) {
                    Text(
                        when {
                            !coreOk -> "请先把必要权限开启"
                            isInitialSetup -> "全部开好了，进入主界面"
                            else -> "完成，返回主界面"
                        }
                    )
                }
            }
        }
    }
}

internal enum class NotificationPermissionAction { REQUEST, OPEN_SETTINGS }

internal fun notificationPermissionAction(
    requestAttempted: Boolean,
    shouldShowRationale: Boolean,
): NotificationPermissionAction = if (!requestAttempted || shouldShowRationale) {
    NotificationPermissionAction.REQUEST
} else {
    NotificationPermissionAction.OPEN_SETTINGS
}

@Composable
private fun PermissionCard(
    title: String,
    desc: String,
    granted: Boolean,
    actionLabel: String = "去开启",
    onGo: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(desc, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (granted) {
                Text("已开启", color = Ink)
            } else {
                Button(
                    onClick = onGo,
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper)
                ) { Text(actionLabel) }
            }
        }
    }
}
