package me.excuse.app.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.text.Collator
import me.excuse.app.category.AndroidApplicationInfoCategoryResolver
import me.excuse.app.category.AppCategory

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val systemCategory: AppCategory?
)

object AppInfoUtil {
    fun loadInstalledApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        val result = ArrayList<InstalledApp>()
        for (ri in infos) {
            val pkg = ri.activityInfo.packageName
            if (!seen.add(pkg)) continue
            if (pkg == context.packageName) continue
            val ai = try { pm.getApplicationInfo(pkg, 0) } catch (e: PackageManager.NameNotFoundException) { continue }
            val isSystem = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            result.add(
                InstalledApp(
                    packageName = pkg,
                    label = pm.getApplicationLabel(ai).toString(),
                    isSystem = isSystem,
                    systemCategory = AndroidApplicationInfoCategoryResolver.resolve(ai)
                )
            )
        }
        // 按 String 的自然序排会把中文按 UTF-16 码位排，看起来是乱的。
        // Collator 走当前 locale 的排序规则（中文即拼音），列表才符合预期。
        val collator = Collator.getInstance()
        result.sortWith(compareBy(collator) { it.label })
        return result
    }

    fun appLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
