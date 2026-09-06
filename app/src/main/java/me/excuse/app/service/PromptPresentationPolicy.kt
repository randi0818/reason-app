package me.excuse.app.service

internal data class PromptSubmission(
    val packageName: String,
    val generation: Long,
)

/** 同包异步任务也必须按请求代际判旧，单看 package 无法识别 A₁→A₂ 的 ABA。 */
internal fun isCurrentGeneration(
    requestGeneration: Long,
    currentGeneration: Long,
): Boolean = requestGeneration == currentGeneration

internal fun isPromptSubmissionCurrent(
    submission: PromptSubmission,
    currentGeneration: Long,
    currentPromptPackage: String?,
): Boolean =
    isCurrentGeneration(submission.generation, currentGeneration) &&
        submission.packageName == currentPromptPackage

internal data class ReadyOrFallbackState(val delivered: Boolean = false)

internal data class ReadyOrFallbackDecision(
    val nextState: ReadyOrFallbackState,
    val shouldPresent: Boolean,
)

/** 前台 ready 回调和 120ms fallback 竞争同一个一次性 gate。 */
internal fun decideReadyOrFallback(
    state: ReadyOrFallbackState,
    promptStillCurrent: Boolean,
): ReadyOrFallbackDecision = when {
    state.delivered || !promptStillCurrent -> ReadyOrFallbackDecision(state, shouldPresent = false)
    else -> ReadyOrFallbackDecision(
        nextState = ReadyOrFallbackState(delivered = true),
        shouldPresent = true,
    )
}
