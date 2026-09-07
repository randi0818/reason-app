package me.excuse.app.data

import me.excuse.app.data.db.AppCategoryOverride
import me.excuse.app.data.db.InterceptEvent
import me.excuse.app.data.db.MonitoredApp
import me.excuse.app.data.db.UsageSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转义是这里唯一真正会出事的地方：理由是自由文本，引号和换行迟早会出现，
 * 一旦漏转义，导出的文件对任何解析器都是坏的，而且要等到某条特定记录才炸。
 */
class UsageExportTest {

    private fun export(
        sessions: List<UsageSession> = emptyList(),
        events: List<InterceptEvent> = emptyList(),
        monitoredApps: List<MonitoredApp> = emptyList(),
        categoryOverrides: List<AppCategoryOverride> = emptyList(),
    ): String = buildString {
        val writer = UsageExportJsonWriter(
            output = this,
            exportedAtMillis = 0L,
            appVersion = "0.1.0",
            timeZoneId = "Asia/Shanghai",
            toIso = { millis -> "T$millis" },
        )
        sessions.forEach(writer::session)
        writer.beginEvents()
        events.forEach(writer::event)
        writer.finish(monitoredApps, categoryOverrides)
    }

    private fun session(
        id: Long = 1L,
        reason: String = "查一条消息",
        endTime: Long? = 2_000L,
    ) = UsageSession(
        id = id,
        packageName = "com.example.app",
        appName = "示例",
        reason = reason,
        reasonNormalized = "查一条消息",
        plannedMinutes = 20,
        startTime = 1_000L,
        endTime = endTime,
        overran = false,
    )

    @Test
    fun escapesQuotesAndBackslashes() {
        val json = export(sessions = listOf(session(reason = """他说"就看一眼"\真的""")))
        assertTrue(json, json.contains("""\"就看一眼\""""))
        assertTrue(json, json.contains("""\\真的"""))
    }

    @Test
    fun escapesNewlinesAndTabs() {
        val json = export(sessions = listOf(session(reason = "第一行\n第二行\t缩进")))
        assertTrue(json, json.contains("""第一行\n第二行\t缩进"""))
        // 字符串字面量内部不能出现裸控制字符，否则 JSON 非法。
        val reasonValue = json.substringAfter("\"reason\": \"").substringBefore("\", ")
        assertFalse("raw control character in: $reasonValue", reasonValue.any { it < ' ' })
    }

    @Test
    fun escapesControlCharactersWithoutAShortForm() {
        val json = export(sessions = listOf(session(reason = "a\u0001b")))
        assertTrue(json, json.contains("""a\u0001b"""))
    }

    @Test
    fun keepsCjkReadableInsteadOfEscapingIt() {
        val json = export(sessions = listOf(session(reason = "查一条消息")))
        assertTrue(json, json.contains("\"查一条消息\""))
    }

    @Test
    fun writesNullForASessionThatNeverEnded() {
        val json = export(sessions = listOf(session(endTime = null)))
        assertTrue(json, json.contains("\"endTime\": null"))
    }

    @Test
    fun writesNullForAnEventWithNoSession() {
        val json = export(
            events = listOf(
                InterceptEvent(
                    id = 7L,
                    packageName = "com.example.app",
                    appName = "示例",
                    outcome = "ABANDONED",
                    at = 5_000L,
                    sessionId = null,
                )
            )
        )
        assertTrue(json, json.contains("\"sessionId\": null"))
        assertTrue(json, json.contains("\"outcome\": \"ABANDONED\""))
    }

    @Test
    fun emitsEmptyArraysRatherThanOmittingTheKeys() {
        val json = export()
        assertTrue(json, json.contains("\"sessions\": []"))
        assertTrue(json, json.contains("\"interceptEvents\": []"))
        assertTrue(json, json.contains("\"monitoredApps\": []"))
        assertTrue(json, json.contains("\"categoryOverrides\": []"))
    }

    /**
     * 名单和分类是换手机之后要手动重建的东西，说明页也承诺了会一起导 ——
     * 漏掉它们不会让文件变成非法 JSON，只会让那句承诺变成谎话。
     */
    @Test
    fun exportsTheMonitoredListAndCategoryOverrides() {
        val json = export(
            monitoredApps = listOf(
                MonitoredApp(
                    packageName = "com.example.app",
                    appName = "示例",
                    enabled = true,
                    addedAt = 3_000L,
                )
            ),
            categoryOverrides = listOf(
                AppCategoryOverride(packageName = "com.example.app", category = "SOCIAL")
            ),
        )
        assertTrue(json, json.contains("\"appName\": \"示例\""))
        assertTrue(json, json.contains("\"addedAt\": \"T3000\""))
        assertTrue(json, json.contains("\"category\": \"SOCIAL\""))
        // enabled 在库里恒为 true（取消勾选是删行），导出它只是噪音。
        assertFalse(json, json.contains("enabled"))
    }

    @Test
    fun doesNotLeakTheInternalDedupKey() {
        val json = export(sessions = listOf(session()))
        assertFalse(json, json.contains("reasonNormalized"))
    }

    @Test
    fun routesEveryTimestampThroughTheInjectedFormatter() {
        val json = export(sessions = listOf(session()))
        assertTrue(json, json.contains("\"startTime\": \"T1000\""))
        assertTrue(json, json.contains("\"endTime\": \"T2000\""))
        assertTrue(json, json.contains("\"exportedAt\": \"T0\""))
        // 裸的 epoch 毫秒不该出现在文件里 —— 读的人不该再去猜时区。
        assertFalse(json, json.contains(": 1000"))
    }

    @Test
    fun separatesRecordsWithCommasAndClosesTheDocument() {
        val json = export(sessions = listOf(session(id = 1L), session(id = 2L)))
        assertTrue(json, json.contains("},\n"))
        assertTrue(json, json.trimEnd().endsWith("}"))
    }
}
