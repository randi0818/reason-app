package me.excuse.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPresentationPolicyTest {
    @Test
    fun submissionRequiresMatchingGenerationAndPackage() {
        val submission = PromptSubmission("com.example.a", generation = 3L)

        assertTrue(isPromptSubmissionCurrent(submission, 3L, "com.example.a"))
        assertFalse(isPromptSubmissionCurrent(submission, 4L, "com.example.a"))
        assertFalse(isPromptSubmissionCurrent(submission, 3L, "com.example.b"))
    }

    @Test
    fun readyAndFallbackDeliverAtMostOnce() {
        val ready = decideReadyOrFallback(ReadyOrFallbackState(), promptStillCurrent = true)
        assertTrue(ready.shouldPresent)

        val fallback = decideReadyOrFallback(ready.nextState, promptStillCurrent = true)
        assertFalse(fallback.shouldPresent)
    }

    @Test
    fun stalePromptIsNotPresentedByReadyOrFallback() {
        val decision = decideReadyOrFallback(
            ReadyOrFallbackState(),
            promptStillCurrent = false,
        )

        assertFalse(decision.shouldPresent)
        assertFalse(decision.nextState.delivered)
    }

    @Test
    fun oldARequestStaysStaleAfterAtoBtoA() {
        val firstA = PromptSubmission("com.example.a", generation = 1L)
        val latestA = PromptSubmission("com.example.a", generation = 3L)

        val oldReady = decideReadyOrFallback(
            state = ReadyOrFallbackState(),
            promptStillCurrent = isPromptSubmissionCurrent(
                submission = firstA,
                currentGeneration = latestA.generation,
                currentPromptPackage = latestA.packageName,
            ),
        )
        assertFalse(oldReady.shouldPresent)
        assertFalse(oldReady.nextState.delivered)

        val latestReady = decideReadyOrFallback(
            state = oldReady.nextState,
            promptStillCurrent = isPromptSubmissionCurrent(
                submission = latestA,
                currentGeneration = latestA.generation,
                currentPromptPackage = latestA.packageName,
            ),
        )
        assertTrue(latestReady.shouldPresent)
        assertTrue(latestReady.nextState.delivered)

        val duplicateFallback = decideReadyOrFallback(
            state = latestReady.nextState,
            promptStillCurrent = true,
        )
        assertFalse(duplicateFallback.shouldPresent)
    }

    @Test
    fun oldSessionLeaveCallbackIsStaleDuringSecondSamePackageGrace() {
        var currentGeneration = 0L
        val firstLeave = ++currentGeneration
        currentGeneration += 1L // grace 内回到 A，取消第一段 leave
        val secondLeave = ++currentGeneration

        assertFalse(isCurrentGeneration(firstLeave, currentGeneration))
        assertTrue(isCurrentGeneration(secondLeave, currentGeneration))
    }
}
