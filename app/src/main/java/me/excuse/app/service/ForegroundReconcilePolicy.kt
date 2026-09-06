package me.excuse.app.service

internal data class UsageStatsForeground(
    val packageName: String,
    val lastTimeUsed: Long,
)

/**
 * 聚合 UsageStats 只能补“比事件流更新”的证据，不能推翻刚看到的 PAUSED/Home/launcher。
 *
 * `lastTimeUsed` 只表示最近出现过，不天然等于当前前台。保留时间戳后，真正漏发的 RESUMED
 * 仍能以更新的时间恢复；刚离开 A 时残留的旧 A 则不会把 SessionLeaving 立刻反转回去。
 */
internal fun shouldApplyUsageStatsForeground(
    state: InterceptStateMachine.State,
    stickyPackage: String?,
    lastForegroundChangeAt: Long?,
    candidate: UsageStatsForeground?,
    monitored: Set<String>,
): Boolean {
    candidate ?: return false
    if (state is InterceptStateMachine.State.Prompting ||
        state is InterceptStateMachine.State.TimeUp
    ) return false
    if (candidate.packageName == stickyPackage) return false
    if (lastForegroundChangeAt != null && candidate.lastTimeUsed <= lastForegroundChangeAt) {
        return false
    }

    // 确认发生在 Home/Recents 上时，1px 稳定页结束后可能留下 InSession +
    // unknown/non-monitored sticky；若 OEM 又漏发目标 RESUMED，只允许更新证据把它
    // 恢复到本 session 自己，绝不接受 launcher 或另一个 app 覆盖新 session。
    if (state is InterceptStateMachine.State.InSession) {
        return state.initialEntryPending &&
            candidate.packageName == state.pkg &&
            candidate.packageName in monitored &&
            (stickyPackage == null || stickyPackage !in monitored)
    }

    val reconcilableState =
        state is InterceptStateMachine.State.Background ||
            state is InterceptStateMachine.State.Unknown ||
            state is InterceptStateMachine.State.SessionLeaving
    if (!reconcilableState) return false

    // sticky=null 表示事件流明确离开或尚未建立前台；只接受更新的 aggregate 证据。
    if (stickyPackage == null) return true

    // 已知一个非监控前台时，只允许更新的“监控 app 进入”推翻它。这样 launcher/
    // 无关 app 不会被另一条同样模糊的 aggregate 记录来回覆盖。
    return stickyPackage !in monitored &&
        candidate.packageName in monitored
}
