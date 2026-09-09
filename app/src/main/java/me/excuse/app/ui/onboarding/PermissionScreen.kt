package me.excuse.app.ui.onboarding

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import me.excuse.app.ui.theme.Disabled
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.MutedSoft
import me.excuse.app.ui.theme.Paper

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
    val isXiaomiRom = PermissionUtils.isXiaomiRom()

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
            ) {
                Text("给一个理由", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "权限与后台",
                    color = Ink,
                    fontSize = 40.sp,
                    lineHeight = 50.sp,
                    fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.height(36.dp))
                Text("必要权限", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PermissionItem(
                        title = "使用情况访问",
                        desc = "用来知道你正在用哪个 app、什么时候切换。看不到屏幕内容、聊天和输入。",
                        granted = usage,
                        onGo = { PermissionUtils.openUsageAccessSettings(context) }
                    )
                    PermissionItem(
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
                        PermissionItem(
                            title = "通知权限",
                            desc = if (!notif && notificationAction == NotificationPermissionAction.OPEN_SETTINGS) {
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
                }

                Spacer(Modifier.height(36.dp))
                Text("后台设置 · 建议检查", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isXiaomiRom) {
                        PermissionItem(
                            title = "省电策略",
                            desc = "强烈建议设为无限制，避免系统暂停后台监控，导致拦截不弹。\n\n应用信息 → 省电策略 → 无限制",
                            granted = false,
                            actionLabel = "去设置",
                            onGo = {
                                if (!PermissionUtils.openAppDetailsSettings(context)) {
                                    Toast.makeText(
                                        context,
                                        "未能打开，请在系统设置中找到本应用的「应用信息」。",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                        )
                        PermissionItem(
                            title = "自启动",
                            desc = "在自启动管理中允许本 app 自启动。省电策略需要单独设置；若打开的是应用信息，可在其中找到「自启动」。",
                            granted = false,
                            actionLabel = "去设置",
                            onGo = {
                                if (PermissionUtils.openAutostartSettings(context) == PermissionUtils.AutostartResult.FAILED) {
                                    Toast.makeText(
                                        context,
                                        "未能打开，请在本应用的「应用信息」中检查自启动。",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                        )
                    } else {
                        // 原生 Android 通常没有独立的自启动页，仍要明确告诉用户按钮会去哪里。
                        PermissionItem(
                            title = if (isAggressiveRom) "自启动 / 后台" else "电池策略",
                            desc = if (isAggressiveRom) {
                                "强烈建议打开厂商的自启动或后台管理，把本 app 设为允许或无限制。"
                            } else {
                                "可按需调整。这台手机通常没有单独的自启动开关；按钮会打开电池优化设置。"
                            },
                            granted = false,
                            actionLabel = "去设置",
                            onGo = { PermissionUtils.openAutostartSettings(context) },
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))

                Button(
                    onClick = {
                        refresh()
                        if (usage && overlay && notif) onAllGranted()
                    },
                    enabled = coreOk,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
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
                            isInitialSetup -> "进入主界面"
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
private fun PermissionItem(
    title: String,
    desc: String,
    granted: Boolean,
    actionLabel: String = "去开启",
    onGo: () -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 说明与授权按钮分开点击，收起说明后仍能直接去设置。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起说明" else "展开说明",
                    ) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (expanded) "−" else "+",
                    modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics {},
                    color = Muted,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.width(14.dp))
            // 状态与按钮共用一列，授权前后和不同项目的展开图标都保持对齐。
            Box(
                modifier = Modifier.width(96.dp).heightIn(min = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (granted) {
                    Text("已开启", color = Muted, fontSize = 12.sp)
                } else {
                    OutlinedButton(
                        onClick = onGo,
                        shape = RoundedCornerShape(0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "$title，$actionLabel" },
                        border = BorderStroke(1.dp, MutedSoft),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    ) { Text(actionLabel, fontSize = 12.sp) }
                }
            }
        }
        if (expanded) {
            Text(
                desc,
                modifier = Modifier.padding(bottom = 10.dp),
                color = Muted,
                fontSize = 15.sp,
                lineHeight = 24.sp,
            )
        }
    }
}
