package me.excuse.app.service

internal data class StartupForegroundDecision(
    val packageName: String?,
    val recoveredFromHistory: Boolean,
)

/** 近期事件优先；只有 seed 跑空时才采用一次性的长窗口 UsageEvents 恢复。 */
internal fun decideStartupForeground(
    seededPackage: String?,
    historyPackage: String?,
): StartupForegroundDecision =
    if (seededPackage != null) {
        StartupForegroundDecision(seededPackage, recoveredFromHistory = false)
    } else {
        StartupForegroundDecision(historyPackage, recoveredFromHistory = historyPackage != null)
    }

internal fun shouldAttemptStartupHistoryRecovery(
    seededPackage: String?,
    isFirstPollForService: Boolean,
): Boolean = seededPackage == null && isFirstPollForService

/** 历史恢复不能跨越本次开机，否则 BOOT_COMPLETED 可能复活上次关机前的旧前台。 */
internal fun startupHistoryStartMillis(
    nowMillis: Long,
    elapsedRealtimeMillis: Long,
    recoveryWindowMillis: Long,
): Long {
    val lookback = minOf(
        elapsedRealtimeMillis.coerceAtLeast(0L),
        recoveryWindowMillis.coerceAtLeast(0L),
    )
    return (nowMillis - lookback).coerceAtMost(nowMillis)
}
