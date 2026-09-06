package me.excuse.app.data

import me.excuse.app.data.db.ReasonOccurrence
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasonWallTest {
    @Test
    fun groupsNormalizedReasonsAndSortsByCount() {
        val wall = aggregateReasonWall(
            listOf(
                occurrence("回工作群消息", "回工作群消息", "微信", 300L),
                occurrence("看教程", "看教程", "哔哩哔哩", 250L),
                occurrence("回工作群消息 ", "回工作群消息", "钉钉", 200L),
                occurrence("看教程", "看教程", "YouTube", 100L),
                occurrence("回工作群消息", "回工作群消息", "微信", 50L),
            )
        )

        assertEquals(listOf(3, 2), wall.map { it.count })
        assertEquals("回工作群消息", wall.first().reason)
        assertEquals(listOf("微信", "钉钉"), wall.first().appNames)
    }

    @Test
    fun equalCountsUseMostRecentReasonFirst() {
        val wall = aggregateReasonWall(
            listOf(
                occurrence("晚点看", "晚点看", "A", 10L),
                occurrence("马上回复", "马上回复", "B", 20L),
            )
        )

        assertEquals(listOf("马上回复", "晚点看"), wall.map { it.reason })
    }

    private fun occurrence(
        reason: String,
        normalized: String,
        appName: String,
        at: Long,
    ) = ReasonOccurrence(reason, normalized, appName, at)
}
