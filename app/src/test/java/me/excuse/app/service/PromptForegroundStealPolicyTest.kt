package me.excuse.app.service

import me.excuse.app.service.InterceptStateMachine.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptForegroundStealPolicyTest {

    private val appA = "com.example.a"
    private val appB = "com.example.b"

    @Test
    fun firstShowPromptTriggersForegroundSteal() {
        val decision = decidePromptForegroundSteal(
            state = PromptForegroundStealState(),
            effect = Effect.ShowPrompt(appA),
        )

        assertTrue(decision.shouldSteal)
        assertEquals(PromptForegroundStealState(appA), decision.nextState)
    }

    @Test
    fun duplicateShowPromptForSameRoundIsDeduplicated() {
        val decision = decidePromptForegroundSteal(
            state = PromptForegroundStealState(appA),
            effect = Effect.ShowPrompt(appA),
        )

        assertFalse(decision.shouldSteal)
        assertEquals(PromptForegroundStealState(appA), decision.nextState)
    }

    @Test
    fun switchingPromptPackageTriggersOneNewSteal() {
        val decision = decidePromptForegroundSteal(
            state = PromptForegroundStealState(appA),
            effect = Effect.ShowPrompt(appB),
        )

        assertTrue(decision.shouldSteal)
        assertEquals(PromptForegroundStealState(appB), decision.nextState)
    }

    @Test
    fun nonPromptEffectsNeverTriggerAndDismissRearmsNextRound() {
        val active = PromptForegroundStealState(appA)
        val timeUp = decidePromptForegroundSteal(active, Effect.ShowTimeUp(appA))
        assertFalse(timeUp.shouldSteal)
        assertEquals(active, timeUp.nextState)

        val dismissed = decidePromptForegroundSteal(timeUp.nextState, Effect.Dismiss)
        assertFalse(dismissed.shouldSteal)
        assertEquals(PromptForegroundStealState(), dismissed.nextState)

        val nextRound = decidePromptForegroundSteal(
            dismissed.nextState,
            Effect.ShowPrompt(appA),
        )
        assertTrue(nextRound.shouldSteal)
    }
}
