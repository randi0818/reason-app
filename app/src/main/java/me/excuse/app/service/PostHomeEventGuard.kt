package me.excuse.app.service

/**
 * Guards the noisy period after this app intentionally sends the user Home.
 *
 * UsageStats lastTimeUsed can still point at the blocked app for a short while,
 * and overlapped UsageEvents queries can surface pre-Home RESUMED events late. The
 * target can also emit one new RESUMED before launcher takeover. Treat both as a
 * bounded transition so they cannot create a ghost prompt on the launcher.
 */
internal class PostHomeEventGuard(
    private val usageStatsSuppressMs: Long,
) {
    private var lastHomeAt: Long = Long.MIN_VALUE
    private var suppressUsageStatsUntil: Long = Long.MIN_VALUE
    private var pendingHomeTarget: String? = null

    fun markHome(targetPackage: String, now: Long) {
        lastHomeAt = now
        suppressUsageStatsUntil = maxOf(suppressUsageStatsUntil, now + usageStatsSuppressMs)
        pendingHomeTarget = targetPackage
    }

    fun completeHome(now: Long) {
        // 退出页已完成窗口交接；旧生命周期仍可迟到，但后续真实进入不能再被 6s 静默吞掉。
        lastHomeAt = now
        suppressUsageStatsUntil = Long.MIN_VALUE
        pendingHomeTarget = null
    }

    fun reset() {
        lastHomeAt = Long.MIN_VALUE
        suppressUsageStatsUntil = Long.MIN_VALUE
        pendingHomeTarget = null
    }

    fun shouldDropStaleResume(
        isResume: Boolean,
        eventTimestamp: Long,
        packageName: String,
        monitored: Set<String>,
        now: Long,
    ): Boolean {
        if (!isResume) return false
        expirePendingTarget(now)

        // overlap query 补出的 Home 前事件沿用原 barrier；只过滤监控 app，不能吞 launcher。
        if (packageName in monitored && eventTimestamp <= lastHomeAt) return true

        val target = pendingHomeTarget ?: return false
        if (packageName == target) {
            // startActivity(HOME) 到 launcher 真正 RESUMED 之间，目标可能短暂补发一条新的
            // RESUMED。它不是用户重进；若放行会 Background→Prompting 后在桌面留下 ghost。
            return true
        }

        // 退出桥页 / launcher 的 RESUMED 不等于 Home 动画已结束。桥页收尾前可能
        // 短暂恢复目标；必须等显式完成回调再放行它。真实切到另一个监控 app 仍可换窗。
        if (packageName !in monitored) return false

        // 真实进入 B 会使 presenter 的旧退出代际失效；这里也结束 A 的静默，
        // 避免 A→B→A 时仍吞掉后一次 A。
        if (eventTimestamp >= lastHomeAt) {
            pendingHomeTarget = null
            suppressUsageStatsUntil = Long.MIN_VALUE
        }
        return false
    }

    fun shouldSkipUsageStatsReconcile(now: Long): Boolean {
        expirePendingTarget(now)
        return now < suppressUsageStatsUntil
    }

    private fun expirePendingTarget(now: Long) {
        if (now < lastHomeAt) {
            // 墙钟回拨后旧 epoch 的 Home barrier/静默终点不能继续挡住新事件。
            reset()
            return
        }
        if (now >= suppressUsageStatsUntil) pendingHomeTarget = null
    }
}
