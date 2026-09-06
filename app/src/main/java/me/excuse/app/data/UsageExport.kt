package me.excuse.app.data

import me.excuse.app.data.db.AppCategoryOverride
import me.excuse.app.data.db.InterceptEvent
import me.excuse.app.data.db.MonitoredApp
import me.excuse.app.data.db.UsageSession

/** 四张表必须来自同一个 Room transaction，导出文件才代表一个确定时刻。 */
data class UsageExportSnapshot(
    val sessions: List<UsageSession>,
    val events: List<InterceptEvent>,
    val monitoredApps: List<MonitoredApp>,
    val categoryOverrides: List<AppCategoryOverride>,
)

/**
 * 把整库序列化成 JSON。
 *
 * 手写而不是用 `org.json`：后者在 JVM 单测里是桩，一调就抛 "not mocked"，
 * 这个函数就没法测了。引 kotlinx.serialization 又是一个新依赖，为一个导出不值得。
 * 需要正确处理的只有字符串转义 —— 理由是自由文本，里面出现引号和换行是迟早的事。
 *
 * 时间统一用带偏移的 ISO-8601（`2026-09-02T14:30:15.123+08:00`）：既无损，
 * 又不需要读的人再去猜时区。[toIso] 由调用方注入，这个函数因此保持纯粹、可测。
 *
 * 不导出 `reasonNormalized` —— 那是去重用的内部键，对读的人只是噪音。
 */
internal fun buildUsageExportJson(
    exportedAtMillis: Long,
    appVersion: String,
    timeZoneId: String,
    sessions: List<UsageSession>,
    events: List<InterceptEvent>,
    monitoredApps: List<MonitoredApp>,
    categoryOverrides: List<AppCategoryOverride>,
    toIso: (Long) -> String,
): String = buildString {
    append("{\n")
    append("  \"exportedAt\": ").appendJsonString(toIso(exportedAtMillis)).append(",\n")
    append("  \"timeZone\": ").appendJsonString(timeZoneId).append(",\n")
    // 只写 app 版本，不写 Room 的 schema version：后者没有可以在运行时安全读到的常量，
    // 写死一个数字就会在下一次 migration 之后变成谎话。要知道 schema 长什么样，从版本号查。
    append("  \"appVersion\": ").appendJsonString(appVersion).append(",\n")

    append("  \"sessions\": ")
    appendJsonArray(sessions) { s ->
        append("{\"id\": ").append(s.id)
        append(", \"packageName\": ").appendJsonString(s.packageName)
        append(", \"appName\": ").appendJsonString(s.appName)
        append(", \"reason\": ").appendJsonString(s.reason)
        append(", \"plannedMinutes\": ").append(s.plannedMinutes)
        append(", \"startTime\": ").appendJsonString(toIso(s.startTime))
        append(", \"endTime\": ").appendNullableIso(s.endTime, toIso)
        append(", \"overran\": ").append(s.overran)
        append("}")
    }
    append(",\n")

    append("  \"interceptEvents\": ")
    appendJsonArray(events) { e ->
        append("{\"id\": ").append(e.id)
        append(", \"packageName\": ").appendJsonString(e.packageName)
        append(", \"appName\": ").appendJsonString(e.appName)
        append(", \"outcome\": ").appendJsonString(e.outcome)
        append(", \"at\": ").appendJsonString(toIso(e.at))
        append(", \"sessionId\": ")
        if (e.sessionId == null) append("null") else append(e.sessionId)
        append("}")
    }
    append(",\n")

    // 名单和分类是配置而不是记录，但换手机之后要手动重建的恰恰是它们，所以一起导。
    // 不写 MonitoredApp.enabled —— 取消勾选是直接删行，这一列在库里恒为 true，导出只是噪音。
    append("  \"monitoredApps\": ")
    appendJsonArray(monitoredApps) { app ->
        append("{\"packageName\": ").appendJsonString(app.packageName)
        append(", \"appName\": ").appendJsonString(app.appName)
        append(", \"addedAt\": ").appendJsonString(toIso(app.addedAt))
        append("}")
    }
    append(",\n")

    append("  \"categoryOverrides\": ")
    appendJsonArray(categoryOverrides) { override ->
        append("{\"packageName\": ").appendJsonString(override.packageName)
        append(", \"category\": ").appendJsonString(override.category)
        append("}")
    }
    append("\n}\n")
}

/** 每条记录单独一行 —— 比压成一行好读、好 diff，又不像缩进 pretty-print 那样把文件撑大一倍。 */
private inline fun <T> StringBuilder.appendJsonArray(
    items: List<T>,
    appendItem: StringBuilder.(T) -> Unit,
) {
    if (items.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    items.forEachIndexed { index, item ->
        append("    ")
        appendItem(item)
        if (index != items.lastIndex) append(",")
        append("\n")
    }
    append("  ]")
}

private fun StringBuilder.appendNullableIso(
    millis: Long?,
    toIso: (Long) -> String,
): StringBuilder {
    if (millis == null) append("null") else appendJsonString(toIso(millis))
    return this
}

private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    for (c in value) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c == '\u000C' -> append("\\f")
            // 其余控制字符没有短写法，只能转成 \u 转义；非 ASCII 直接原样输出，
            // UTF-8 的中文本来就是合法 JSON，转义只会让文件更难读。
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
    return this
}
