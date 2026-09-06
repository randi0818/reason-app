package me.excuse.app.ui.onboarding

import android.app.AppOpsManager
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.ActivityCompat

object PermissionUtils {

    fun hasUsageAccess(context: Context): Boolean {
        val aom = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = aom.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasOverlay(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun hasNotification(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun shouldShowNotificationPermissionRationale(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                return ActivityCompat.shouldShowRequestPermissionRationale(
                    current,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return false
    }

    fun openNotificationSettings(context: Context) {
        val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(notificationSettings)
        } catch (_: Exception) {
            val appDetails = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(appDetails)
        }
    }

    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        context.startActivity(intent)
    }

    enum class AutostartResult { OPENED_VENDOR, OPENED_BATTERY, OPENED_APP_DETAILS, FAILED }

    /**
     * 各厂商 ROM 的自启动 / 后台保活页路径。
     * - 国产 ROM (小米/华为/OPPO/vivo/三星): 跳厂商专门的自启动管理页
     * - 原生 Android (Pixel/Nothing/OnePlus 海外版等): 没有这种页，跳"电池优化白名单"
     * - 都打不开就跳应用详情页
     */
    fun openAutostartSettings(context: Context): AutostartResult {
        val vendorIntents = listOf(
            // 小米
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            // 华为
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),
            // OPPO
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            // vivo
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            // 三星
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        )
        for (intent in vendorIntents) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                try {
                    context.startActivity(intent)
                    return AutostartResult.OPENED_VENDOR
                } catch (_: Exception) {}
            }
        }

        // 原生 Android 没有"自启动"页，跳"电池优化白名单"——所有手机都有
        try {
            val battery = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            if (context.packageManager.resolveActivity(battery, 0) != null) {
                context.startActivity(battery)
                return AutostartResult.OPENED_BATTERY
            }
        } catch (_: Exception) {}

        // 最后兜底：应用详情页
        try {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            fallback.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(fallback)
            return AutostartResult.OPENED_APP_DETAILS
        } catch (_: Exception) {}
        return AutostartResult.FAILED
    }

    /** 用机型判断是否为国产 ROM。原生 ROM 上"自启动"基本无关紧要，可以淡化提示。 */
    fun isVendorRomLikelyAggressive(): Boolean {
        val mfr = Build.MANUFACTURER.lowercase()
        return mfr.contains("xiaomi") || mfr.contains("redmi") ||
                mfr.contains("huawei") || mfr.contains("honor") ||
                mfr.contains("oppo") || mfr.contains("realme") ||
                mfr.contains("vivo") || mfr.contains("iqoo") ||
                mfr.contains("meizu") || mfr.contains("samsung")
    }
}
