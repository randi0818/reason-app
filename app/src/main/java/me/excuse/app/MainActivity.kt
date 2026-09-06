package me.excuse.app

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import me.excuse.app.service.AppMonitorService
import me.excuse.app.ui.nav.AppNav
import me.excuse.app.ui.onboarding.PermissionScreen
import me.excuse.app.ui.onboarding.PermissionUtils
import me.excuse.app.ui.theme.ExcuseTheme
import me.excuse.app.ui.theme.LocalAppColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主界面故意不加 FLAG_SECURE：进这一页是用户自己的动作，看的也是自己的数据，
        // 挡截图挡不住"有人站在旁边看屏幕"，只挡住了本人想分享、想留档、想发 bug 反馈。
        // 悬浮窗不一样 —— 它不请自来，可能盖在录屏/投屏上，那边的 FLAG_SECURE 保留。
        //
        // 摘掉之后唯一真正失去的是最近任务缩略图的遮挡，所以用专门的开关补回来，
        // 而不是靠一个连截图一起禁掉的粗粒度 flag。API 33 以下拿不到，只能露出。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        setContent {
            ExcuseTheme {
                val ctx = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                // 跟随主题动态刷新系统栏配色（status bar / navigation bar）
                val appColors = LocalAppColors.current
                val view = LocalView.current
                SideEffect {
                    applySystemBarColors(view, appColors.bg.toArgb(), appColors.isDark)
                }

                fun computeOk(): Boolean =
                    PermissionUtils.hasUsageAccess(ctx) &&
                            PermissionUtils.hasOverlay(ctx) &&
                            PermissionUtils.hasNotification(ctx)

                // 把权限状态变成真正的 Compose state，否则 MainActivity 永远不会重组
                var permissionsOk by remember { mutableStateOf(computeOk()) }
                var redoMode by remember { mutableStateOf(false) }

                // 从系统设置切回来时刷新一次
                DisposableEffect(lifecycleOwner) {
                    val obs = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            permissionsOk = computeOk()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }

                val showOnboarding = redoMode || !permissionsOk

                // 从设置进入权限检查后，系统返回退回主界面（不要 finish app）。
                // 首次真正引导阶段（!permissionsOk）不拦截 —— 让 back 正常退出 app，避免卡死。
                BackHandler(enabled = redoMode && permissionsOk) {
                    redoMode = false
                }

                if (showOnboarding) {
                    PermissionScreen(
                        autoContinueWhenGranted = !redoMode,
                        onAllGranted = {
                            redoMode = false
                            permissionsOk = true  // 显式触发重组
                            AppMonitorService.start(ctx)
                        },
                    )
                } else {
                    LaunchedEffect(Unit) { AppMonitorService.start(ctx) }
                    AppNav(onRedoPermissions = { redoMode = true })
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarColors(view: View, backgroundColor: Int, isDark: Boolean) {
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}
