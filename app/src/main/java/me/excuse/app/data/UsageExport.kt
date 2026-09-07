package me.excuse.app.data

import me.excuse.app.data.db.AppCategoryOverride
import me.excuse.app.data.db.InterceptEvent
import me.excuse.app.data.db.MonitoredApp
import me.excuse.app.data.db.UsageSession

interface UsageExportConsumer {
    fun session(session: UsageSession)
    fun beginEvents()
    fun event(event: InterceptEvent)
    fun finish(monitoredApps: List<MonitoredApp>, categoryOverrides: List<AppCategoryOverride>)
}

/** 直接写 Appendable，生产环境使用缓冲 Writer；JVM 测试可用 StringBuilder 检查转义。 */
internal class UsageExportJsonWriter(
    private val output: Appendable,
    exportedAtMillis: Long,
    appVersion: String,
    timeZoneId: String,
    private val toIso: (Long) -> String,
) : UsageExportConsumer {
    private var hasRecords = false

    init {
        output.apply {
            append("{\n")
            append("  \"exportedAt\": ").appendJsonString(toIso(exportedAtMillis)).append(",\n")
            append("  \"timeZone\": ").appendJsonString(timeZoneId).append(",\n")
            append("  \"appVersion\": ").appendJsonString(appVersion).append(",\n")
            append("  \"sessions\": [")
        }
    }

    override fun session(session: UsageSession) = with(output) {
        beginRecord()
        append("{\"id\": ").append(session.id.toString())
        append(", \"packageName\": ").appendJsonString(session.packageName)
        append(", \"appName\": ").appendJsonString(session.appName)
        append(", \"reason\": ").appendJsonString(session.reason)
        append(", \"plannedMinutes\": ").append(session.plannedMinutes.toString())
        append(", \"startTime\": ").appendJsonString(toIso(session.startTime))
        append(", \"endTime\": ").appendNullableIso(session.endTime, toIso)
        append(", \"overran\": ").append(session.overran.toString())
        append("}")
        Unit
    }

    override fun beginEvents() {
        endRecords()
        output.append(",\n  \"interceptEvents\": [")
    }

    override fun event(event: InterceptEvent) = with(output) {
        beginRecord()
        append("{\"id\": ").append(event.id.toString())
        append(", \"packageName\": ").appendJsonString(event.packageName)
        append(", \"appName\": ").appendJsonString(event.appName)
        append(", \"outcome\": ").appendJsonString(event.outcome)
        append(", \"at\": ").appendJsonString(toIso(event.at))
        append(", \"sessionId\": ").append(event.sessionId?.toString() ?: "null")
        append("}")
        Unit
    }

    override fun finish(
        monitoredApps: List<MonitoredApp>,
        categoryOverrides: List<AppCategoryOverride>,
    ) = with(output) {
        endRecords()
        append(",\n  \"monitoredApps\": ")
        appendJsonArray(monitoredApps) { app ->
            append("{\"packageName\": ").appendJsonString(app.packageName)
            append(", \"appName\": ").appendJsonString(app.appName)
            append(", \"addedAt\": ").appendJsonString(toIso(app.addedAt))
            append("}")
        }
        append(",\n  \"categoryOverrides\": ")
        appendJsonArray(categoryOverrides) { override ->
            append("{\"packageName\": ").appendJsonString(override.packageName)
            append(", \"category\": ").appendJsonString(override.category)
            append("}")
        }
        append("\n}\n")
        Unit
    }

    private fun beginRecord() {
        output.append(if (hasRecords) ",\n    " else "\n    ")
        hasRecords = true
    }

    private fun endRecords() {
        output.append(if (hasRecords) "\n  ]" else "]")
        hasRecords = false
    }
}
/** 每条记录单独一行 —— 比压成一行好读、好 diff，又不像缩进 pretty-print 那样把文件撑大一倍。 */
private inline fun <T> Appendable.appendJsonArray(
    items: List<T>,
    appendItem: Appendable.(T) -> Unit,
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

private fun Appendable.appendNullableIso(
    millis: Long?,
    toIso: (Long) -> String,
): Appendable {
    if (millis == null) append("null") else appendJsonString(toIso(millis))
    return this
}

private fun Appendable.appendJsonString(value: String): Appendable {
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
