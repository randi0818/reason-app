package me.excuse.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasonReuseHintTest {
    @Test
    fun uniqueReasonHasNoReuseHint() {
        assertNull(nextReasonUseNumber("第一次", emptyMap()))
    }

    @Test
    fun repeatedReasonShowsTheUpcomingUseNumber() {
        val counts = mapOf("回工作群消息" to 1)

        assertEquals(2, nextReasonUseNumber("  回工作群消息　", counts))
        assertTrue(canConfirmReason(submitting = false, reason = "回工作群消息", minutes = 5))
    }

    @Test
    fun repeatedReasonUsesItsExistingCount() {
        assertEquals(5, nextReasonUseNumber("reply", mapOf("reply" to 4)))
    }
}
