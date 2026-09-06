package me.excuse.app.service

/**
 * 目标 app 仍是已观测的前台时，确认只需拆掉 overlay。
 *
 * 重发 launcher intent 可能把通知、分享或深链打开的页面带回首页；只有
 * 1px 前台 Activity 或用户切换确实使目标不再前台时，才需要恢复它。
 */
internal fun shouldRestorePromptTarget(
    targetPackage: String,
    observedForegroundPackage: String?,
): Boolean = observedForegroundPackage != targetPackage

internal data class PromptConfirmationPlan(
    val shouldLaunchTarget: Boolean,
    val shouldFinishRemovedSession: Boolean,
)

/** DB start 返回后重新以最新名单决定：恢复目标，或立即收尾刚确认的已移除 session。 */
internal fun planPromptConfirmation(
    targetPackage: String,
    observedForegroundPackage: String?,
    monitored: Set<String>,
): PromptConfirmationPlan {
    val stillMonitored = targetPackage in monitored
    return PromptConfirmationPlan(
        shouldLaunchTarget = stillMonitored && shouldRestorePromptTarget(
            targetPackage = targetPackage,
            observedForegroundPackage = observedForegroundPackage,
        ),
        shouldFinishRemovedSession = !stillMonitored,
    )
}

internal data class HomeRequestPlan(
    /** 可可靠归属的受监控前台；未知时由退出桥页使用用户明确取消的目标。 */
    val guardPackage: String?,
)

/**
 * 为退出期间的旧事件保护选择实际目标，不把 launcher/Recents 错当成受监控 app。
 *
 * launcher / Recents、无关 app、未知前台，以及 PAUSED 消歧中的 sticky 不足以归属目标。
 * 以前直接 HOME 时对这些场景开启静默，会把 no-op 当成功并吞掉重进；现在 presenter
 * 启动独立退出桥页，用实际窗口交接回调结束保护，所以 null 时可安全退回用户取消的包。
 *
 * 若实际观测到的是另一个受监控 app，guard 必须跟随那个实际前台，而不是已经取消的旧 prompt。
 * 判据用“是否受监控”而不是查询 launcher 包，避免 API 30+ 包可见性让 HOME activity 查询静默失效。
 */
internal fun planHomeRequest(
    targetPackage: String,
    observedForegroundPackage: String?,
    observedForegroundClassName: String?,
    hasPendingPauseFor: (String) -> Boolean,
    isMonitored: (String) -> Boolean,
    stabilizerPackage: String,
    stabilizerClassName: String,
): HomeRequestPlan {
    val guardPackage = when {
        observedForegroundPackage == null -> null
        observedForegroundPackage == stabilizerPackage &&
            observedForegroundClassName == stabilizerClassName -> targetPackage
        observedForegroundPackage != targetPackage &&
            !isMonitored(observedForegroundPackage) -> null
        hasPendingPauseFor(observedForegroundPackage) -> null
        else -> observedForegroundPackage
    }
    return HomeRequestPlan(guardPackage = guardPackage)
}
