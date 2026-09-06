package me.excuse.app.service

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings

internal data class MonitoringPermissionState(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val notification: Boolean,
) {
    val isReady: Boolean
        get() = usageAccess && overlay && notification
}

internal object MonitoringPermissions {
    fun current(context: Context): MonitoringPermissionState {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val usageAccess = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
        val notification =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        return MonitoringPermissionState(
            usageAccess = usageAccess,
            overlay = Settings.canDrawOverlays(context),
            notification = notification,
        )
    }
}

/**
 * 特殊权限没有普通广播。监听可观测的 AppOps，避免把昂贵的权限查询塞进 100ms
 * 前台检测循环；通知运行时权限由 service 既有的低频通知循环和 effect 前门控复核。
 */
internal class MonitoringPermissionObserver(
    context: Context,
    private val onPermissionChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private var started = false

    private val appOpsListener = AppOpsManager.OnOpChangedListener { _, packageName ->
        if (packageName == null || packageName == appContext.packageName) {
            onPermissionChanged()
        }
    }
    fun start() {
        if (started) return
        started = true
        listOf(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
        ).forEach { operation ->
            try {
                appOps.startWatchingMode(operation, appContext.packageName, appOpsListener)
            } catch (_: RuntimeException) {
                // onStart/screen-on/effect 前仍有同步门控；个别 ROM 拒绝监听时不会失去安全边界。
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            appOps.stopWatchingMode(appOpsListener)
        } catch (_: RuntimeException) {}
    }
}
