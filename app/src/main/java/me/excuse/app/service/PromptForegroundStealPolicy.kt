package me.excuse.app.service

import me.excuse.app.service.InterceptStateMachine.Effect

/** 本轮已经为哪个 prompt 抢过前台；overlay dismiss 后清空。 */
internal data class PromptForegroundStealState(
    val promptedPackage: String? = null,
)

internal data class PromptForegroundStealDecision(
    val shouldSteal: Boolean,
    val nextState: PromptForegroundStealState,
)

/**
 * 纯函数 gate：只有真正的 [Effect.ShowPrompt] 才可能抢前台，同一轮同包只抢一次。
 * 其他 effect 保持 gate；[Effect.Dismiss] 结束本轮，使同一 app 下次进入时可以重新提示。
 */
internal fun decidePromptForegroundSteal(
    state: PromptForegroundStealState,
    effect: Effect,
): PromptForegroundStealDecision = when (effect) {
    is Effect.ShowPrompt -> PromptForegroundStealDecision(
        shouldSteal = state.promptedPackage != effect.pkg,
        nextState = PromptForegroundStealState(effect.pkg),
    )
    Effect.Dismiss -> PromptForegroundStealDecision(
        shouldSteal = false,
        nextState = PromptForegroundStealState(),
    )
    else -> PromptForegroundStealDecision(
        shouldSteal = false,
        nextState = state,
    )
}
