package me.excuse.app

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.excuse.app.service.AppMonitorService
import me.excuse.app.ui.nav.AppNav
import me.excuse.app.ui.onboarding.PermissionScreen
import me.excuse.app.ui.onboarding.PermissionUtils
import me.excuse.app.ui.onboarding.PrivacyIntroScreen
import me.excuse.app.ui.onboarding.StartupPage
import me.excuse.app.ui.onboarding.resolveStartupUiState
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

        // 保留系统启动画面直到正确页面已完成组合；仅等磁盘读取仍可能抢先画出空帧。
        // 不阻塞主线程、不加固定等待，首帧放行后移除监听。
        var firstFrameReady = false
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!firstFrameReady) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })

        setContent {
            val ctx = LocalContext.current
            val services = ctx.appServices
            val preferences by services.startupPreferencesFlow.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current

            fun computeOk(): Boolean =
                PermissionUtils.hasUsageAccess(ctx) &&
                        PermissionUtils.hasOverlay(ctx) &&
                        PermissionUtils.hasNotification(ctx)

            // 把权限状态变成真正的 Compose state，否则 MainActivity 永远不会重组
            var permissionsOk by remember { mutableStateOf(computeOk()) }
            var redoMode by rememberSaveable { mutableStateOf(false) }
            // 开齐必要权限不代表已看完后台设置；离开权限页必须由用户确认。
            // 系统设置期间发生重建或转屏，也要保留这次尚未完成的引导。
            var permissionSetupPending by rememberSaveable { mutableStateOf(!permissionsOk) }

            // 从系统设置切回来时刷新一次
            DisposableEffect(lifecycleOwner) {
                val obs = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        val granted = computeOk()
                        if (!granted) permissionSetupPending = true
                        permissionsOk = granted
                    }
                }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }

            val startup = resolveStartupUiState(preferences, permissionsOk, redoMode, permissionSetupPending)
                ?: return@setContent

            ExcuseTheme(mode = startup.themeMode) {
                // 跟随主题动态刷新系统栏配色（status bar / navigation bar）
                val appColors = LocalAppColors.current
                val view = LocalView.current
                SideEffect {
                    applySystemBarColors(view, appColors.bg.toArgb(), appColors.isDark)
                    firstFrameReady = true
                }

                // 从设置进入权限检查后，系统返回退回主界面（不要 finish app）。
                // 首次引导即使权限已齐也不拦截 —— 让 back 正常退出 app，避免卡死。
                BackHandler(enabled = redoMode && permissionsOk) {
                    redoMode = false
                    permissionSetupPending = false
                }

                when (startup.page) {
                    StartupPage.PRIVACY -> PrivacyIntroScreen(onContinue = {
                        // 由持久化后的 Flow 驱动切屏，退出或转屏后也不会丢掉确认状态。
                        permissionSetupPending = true
                        lifecycleScope.launch { services.prefs.setOnboarded(true) }
                    })
                    StartupPage.PERMISSIONS -> PermissionScreen(
                        isInitialSetup = !redoMode,
                        onAllGranted = {
                            redoMode = false
                            permissionSetupPending = false
                            permissionsOk = true  // 显式触发重组
                            AppMonitorService.start(ctx)
                        },
                    )
                    StartupPage.MAIN -> {
                        DisposableEffect(lifecycleOwner) {
                            // 后台撤权会停服；若后台又恢复权限，MAIN 可能从未离开组合。
                            // 每次回到前台都尝试恢复，start 自行复核权限且不重置已有 session。
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) AppMonitorService.start(ctx)
                            }
                            // 已处于 resumed 时，注册会补发 ON_RESUME，也覆盖首次读完偏好的进入。
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                        AppNav(onRedoPermissions = { redoMode = true })
                    }
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
