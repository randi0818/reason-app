package me.excuse.app.service

import me.excuse.app.service.InterceptStateMachine.Effect

internal sealed interface TimeUpExtensionAttempt {
    data class Applied(
        val effects: List<Effect>,
        val session: SessionStore.Session,
    ) : TimeUpExtensionAttempt

    data class TimerMissing(
        val effects: List<Effect>,
    ) : TimeUpExtensionAttempt

    data object Rejected : TimeUpExtensionAttempt
}

internal fun requestTimeUpExtension(
    stateMachine: InterceptStateMachine,
    sessionStore: SessionStore,
    pkg: String,
    addMinutes: Int,
    now: Long,
    onElapsed: () -> Unit,
): TimeUpExtensionAttempt {
    val effects = stateMachine.onTimeUpExtended(pkg, addMinutes, now)
    if (effects.isEmpty()) return TimeUpExtensionAttempt.Rejected

    val session = sessionStore.extend(pkg, addMinutes, onElapsed)
    return if (session != null) {
        TimeUpExtensionAttempt.Applied(effects, session)
    } else {
        TimeUpExtensionAttempt.TimerMissing(effects)
    }
}
