package me.excuse.app.service

enum class InterceptOutcome {
    STARTED,
    ABANDONED,
    TIMEOUT,
}

/**
 * 纯 Kotlin 拦截状态机。
 *
 * 唯一真相源是 (foreground, monitored, now)。Service 把 UsageEvents 解释成 sticky 前台
 * pkg（带 PAUSED + class 消歧 + 200ms gap 启发式），逐次喂给 [tick]，并按顺序执行 [Effect]。
 *
 * 设计选择：状态机进入 Prompting 时只发 [Effect.ShowPrompt]，不发 sendHome；service 在执行新的
 * ShowPrompt 时先用自己的 1px 透明 Activity 让目标 app onPause，再立即挂 overlay。这个前台切换
 * 只稳定提示首帧，不改变 prompt 的选择语义。只有用户主动取消、超时、到点退出时才 sendHome。
 *
 * 状态机自己不调系统 API，不持 timer / Window / DB；JVM 单测可覆盖 C3/C4/D1 等边界。
 */
class InterceptStateMachine(
    private val initialPromptTimeoutMs: Long = 10_000L,
    private val interactionPromptTimeoutMs: Long = 20_000L,
    private val maxExtensions: Int = 1,
    private val sessionLeaveGraceMs: Long = 10_000L,
) {

    sealed interface State {
        data object Unknown : State
        data class Background(val pkg: String?) : State
        data class Prompting(val pkg: String, val shownAt: Long) : State
        data class InSession(
            val pkg: String,
            val startedAt: Long,
            val plannedMinutes: Int,
            val extensionCount: Int,
            val initialEntryPending: Boolean,
        ) : State
        /** 用户离开 session pkg 后的宽限态。计时到才真正 finalize；期间回同一 app 直接续上。 */
        data class SessionLeaving(
            val pkg: String,
            val startedAt: Long,
            val plannedMinutes: Int,
            val extensionCount: Int,
            val leftAt: Long,
        ) : State
        data class TimeUp(
            val pkg: String,
            val startedAt: Long,
            val plannedMinutes: Int,
            val extensionCount: Int,
        ) : State
    }

    sealed interface Effect {
        /** 返回桌面完成后再收窗，避免 Recents 在退出动画期间暴露可点击的目标卡片。 */
        data class SendHome(val pkg: String) : Effect
        data class ShowPrompt(val pkg: String) : Effect
        data class ShowTimeUp(val pkg: String) : Effect
        data object Dismiss : Effect
        data class FinishSession(val pkg: String, val endedAt: Long, val overran: Boolean) : Effect
        data class RecordInterceptOutcome(
            val pkg: String,
            val outcome: InterceptOutcome,
        ) : Effect
        data object RefreshNotification : Effect
        data class SchedulePromptTimeout(val pkg: String, val durationMs: Long) : Effect
        data object CancelPromptTimeout : Effect
        /** 离开 session pkg 后宽限计时；到点回调 [onSessionLeaveExpired]。 */
        data class ScheduleSessionLeave(val pkg: String, val delayMs: Long) : Effect
        /** 取消未到点的宽限计时（用户回来了，或被其他路径 finalize）。 */
        data object CancelSessionLeave : Effect
    }

    var state: State = State.Unknown
        private set

    fun tick(
        foreground: String?,
        monitored: Set<String>,
        now: Long,
        entryEdge: Boolean = false,
    ): List<Effect> {
        return when (val s = state) {
            is State.Unknown, is State.Background -> handleNoOverlayTick(foreground, monitored, now)
            is State.Prompting -> handlePromptingTick(s, foreground, monitored, now, entryEdge)
            is State.InSession -> handleSessionTick(s, foreground, monitored, now, entryEdge)
            is State.SessionLeaving -> handleSessionLeavingTick(s, foreground, monitored, now)
            is State.TimeUp -> handleTimeUpTick(s, foreground, monitored, now)
        }
    }

    private fun handleNoOverlayTick(
        foreground: String?,
        monitored: Set<String>,
        now: Long,
    ): List<Effect> {
        if (foreground == null || foreground !in monitored) {
            state = State.Background(foreground)
            return emptyList()
        }
        return enterPrompting(foreground, now)
    }

    private fun handlePromptingTick(
        s: State.Prompting,
        foreground: String?,
        monitored: Set<String>,
        now: Long,
        entryEdge: Boolean,
    ): List<Effect> {
        if (foreground == s.pkg) {
            // The prompt is already the active decision point for this package.
            // Home/Recents detours can produce a fresh RESUMED edge for the same
            // app; re-showing here looks like a ghost popup on the launcher.
            return emptyList()
        }
        // Prompting is an outstanding attempt to open a monitored app. If the user
        // detours through Home/Recents before the overlay is visibly mounted, keep
        // the prompt alive; dismissing here is the classic quick-bypass race.
        if (foreground == null || foreground !in monitored) {
            return emptyList()
        }
        // 切到了另一个受监控 app：取消旧 prompt 的超时计时器，直接复用同一悬浮窗
        // 让 [enterPrompting] 通过 setContent 把内容换成新 app。
        // 不发 Dismiss —— 不要让 overlay 经历 GONE→VISIBLE 的 flag/visibility 翻转，
        // 否则 Compose recomposition 跟 WindowManager updateViewLayout 抢窗，
        // 第一帧偶尔渲染的是上一个 pkg 的内容（D1/D2 实测 race）。
        return listOf<Effect>(Effect.CancelPromptTimeout) +
            enterPrompting(foreground, now)
    }

    private fun handleSessionTick(
        s: State.InSession,
        foreground: String?,
        monitored: Set<String>,
        now: Long,
        entryEdge: Boolean,
    ): List<Effect> {
        // 名单是实时配置。用户把当前 app 移出名单后，旧 session 不能继续计时、到点再弹窗；
        // 也不能因为配置变化把人赶回桌面。若此刻实际切到了另一个受监控 app，照常提示它。
        if (s.pkg !in monitored) {
            val finished = listOf<Effect>(
                Effect.FinishSession(s.pkg, endedAt = now, overran = s.extensionCount > 0),
                Effect.RefreshNotification,
            )
            if (foreground != null && foreground in monitored) {
                return finished + enterPrompting(foreground, now)
            }
            state = State.Background(foreground)
            return finished
        }
        if (foreground == s.pkg) {
            if (!entryEdge) return emptyList()
            if (s.initialEntryPending) {
                state = s.copy(initialEntryPending = false)
                return emptyList()
            }
            return listOf<Effect>(
                Effect.FinishSession(s.pkg, endedAt = now, overran = s.extensionCount > 0),
                Effect.RefreshNotification,
            ) + enterPrompting(s.pkg, now)
        }
        // 切到另一个受监控 app：立刻结束当前 session，弹新 app 拦截窗，不进宽限期
        if (foreground != null && foreground in monitored) {
            return listOf<Effect>(
                Effect.FinishSession(s.pkg, endedAt = now, overran = s.extensionCount > 0),
                Effect.RefreshNotification,
            ) + enterPrompting(foreground, now)
        }
        // 切到 launcher / 非监控 / null：进宽限期，等 [sessionLeaveGraceMs] 内回来续上，否则到点 finalize
        state = State.SessionLeaving(
            pkg = s.pkg,
            startedAt = s.startedAt,
            plannedMinutes = s.plannedMinutes,
            extensionCount = s.extensionCount,
            leftAt = now,
        )
        return listOf(
            Effect.ScheduleSessionLeave(s.pkg, sessionLeaveGraceMs),
            Effect.RefreshNotification,
        )
    }

    private fun handleSessionLeavingTick(
        s: State.SessionLeaving,
        foreground: String?,
        monitored: Set<String>,
        now: Long,
    ): List<Effect> {
        // delay 回调是主路径，但主线程拥堵或协程调度延迟时，10 秒之后的前台事件可能先到。
        // tick 自己也守住 deadline，避免“回调晚了一拍”把久离误当成宽限内重入。
        if (s.pkg !in monitored || hasSessionLeaveExpired(s, now)) {
            return finishLeavingAndFollowForeground(s, foreground, monitored, now)
        }
        if (foreground == s.pkg) {
            // 用户回来了。续上 session，不重弹。initialEntryPending 已经是 false（不是初次进入）。
            state = State.InSession(
                pkg = s.pkg,
                startedAt = s.startedAt,
                plannedMinutes = s.plannedMinutes,
                extensionCount = s.extensionCount,
                initialEntryPending = false,
            )
            return listOf(Effect.CancelSessionLeave, Effect.RefreshNotification)
        }
        if (foreground != null && foreground in monitored) {
            // 切到另一个受监控 app：立刻 finalize 当前 session，进入新 app 拦截
            state = State.Prompting(foreground, now)
            return listOf<Effect>(
                Effect.CancelSessionLeave,
                Effect.FinishSession(s.pkg, endedAt = now, overran = s.extensionCount > 0),
                Effect.RefreshNotification,
            ) + listOf(
                Effect.ShowPrompt(foreground),
                Effect.SchedulePromptTimeout(foreground, initialPromptTimeoutMs),
            )
        }
        // 仍在 launcher / 非监控 / null：保持 SessionLeaving，等计时器到点
        return emptyList()
    }

    private fun hasSessionLeaveExpired(s: State.SessionLeaving, now: Long): Boolean =
        now >= s.leftAt && now - s.leftAt >= sessionLeaveGraceMs

    private fun finishLeavingAndFollowForeground(
        s: State.SessionLeaving,
        foreground: String?,
        monitored: Set<String>,
        now: Long,
    ): List<Effect> {
        val finished = listOf<Effect>(
            Effect.CancelSessionLeave,
            Effect.FinishSession(
                s.pkg,
                endedAt = s.leftAt,
                overran = s.extensionCount > 0,
            ),
            Effect.RefreshNotification,
        )
        if (foreground != null && foreground in monitored) {
            return finished + enterPrompting(foreground, now)
        }
        state = State.Background(foreground)
        return finished
    }

    private fun handleTimeUpTick(
        s: State.TimeUp,
        foreground: String?,
        monitored: Set<String>,
        now: Long,
    ): List<Effect> {
        if (foreground == s.pkg && s.pkg in monitored) return emptyList()
        // 到点窗本身会在 deadline 之后才出现；等待它显示或选择「退出」不等于继续使用。
        // 只有用户主动延期才算超时，extensionCount 是稳定且不受调度延迟影响的依据。
        val overran = s.extensionCount > 0
        val finish = Effect.FinishSession(s.pkg, endedAt = now, overran = overran)
        if (foreground != null && foreground in monitored) {
            // TimeUp 也是一个已经可见的 overlay。切到另一个受监控 app 时原地换成 prompt，
            // 不经历 GONE→VISIBLE，和 Prompting/InSession 的 A→B 路径保持同一不变式。
            return listOf<Effect>(finish, Effect.RefreshNotification) +
                enterPrompting(foreground, now)
        }
        state = State.Background(foreground)
        return listOf<Effect>(
            finish,
            Effect.Dismiss,
            Effect.RefreshNotification,
        )
    }

    private fun enterPrompting(pkg: String, now: Long): List<Effect> {
        state = State.Prompting(pkg, now)
        return listOf(
            Effect.ShowPrompt(pkg),
            Effect.SchedulePromptTimeout(pkg, initialPromptTimeoutMs),
        )
    }

    fun onPromptConfirmed(
        pkg: String,
        plannedMinutes: Int,
        now: Long,
    ): List<Effect> {
        val s = state as? State.Prompting ?: return emptyList()
        if (s.pkg != pkg) return emptyList()
        state = State.InSession(
            pkg = pkg,
            startedAt = now,
            plannedMinutes = plannedMinutes,
            extensionCount = 0,
            initialEntryPending = true,
        )
        return listOf(
            Effect.CancelPromptTimeout,
            Effect.Dismiss,
            Effect.RefreshNotification,
        )
    }

    fun onPromptCancelled(pkg: String, now: Long): List<Effect> {
        val s = state as? State.Prompting ?: return emptyList()
        if (s.pkg != pkg) return emptyList()
        state = State.Background(null)
        return listOf(
            Effect.CancelPromptTimeout,
            Effect.RecordInterceptOutcome(pkg, InterceptOutcome.ABANDONED),
            Effect.SendHome(pkg),
        )
    }

    fun onPromptTimedOut(pkg: String, now: Long): List<Effect> {
        val s = state as? State.Prompting ?: return emptyList()
        if (s.pkg != pkg) return emptyList()
        state = State.Background(null)
        return listOf(
            Effect.RecordInterceptOutcome(pkg, InterceptOutcome.TIMEOUT),
            Effect.SendHome(pkg),
        )
    }

    fun onPromptInteraction(pkg: String, now: Long): List<Effect> {
        val s = state as? State.Prompting ?: return emptyList()
        if (s.pkg != pkg) return emptyList()
        return listOf(Effect.SchedulePromptTimeout(pkg, interactionPromptTimeoutMs))
    }

    fun onSessionTimeElapsed(pkg: String, now: Long): List<Effect> {
        return when (val s = state) {
            is State.InSession -> {
                if (s.pkg != pkg) return emptyList()
                state = State.TimeUp(
                    pkg = pkg,
                    startedAt = s.startedAt,
                    plannedMinutes = s.plannedMinutes,
                    extensionCount = s.extensionCount,
                )
                listOf(Effect.ShowTimeUp(pkg))
            }
            // 人已经离开这个 app 了，只是还在宽限期里。这时候弹「时间到了」会盖在桌面 /
            // 别的 app 上，而「继续用 +5 分钟」对一个已经退出的 app 也没有意义 —— 拦截目的
            // 早就达成了。直接结束且不 SendHome；是否超时只保留此前有没有主动延期。
            // 宽限期外到点走的是 onSessionLeaveExpired，落点完全一致。
            // 之后再回到这个 app，会被当作一次新的进入重新弹拦截窗。
            is State.SessionLeaving -> {
                if (s.pkg != pkg) return emptyList()
                state = State.Background(null)
                listOf(
                    Effect.CancelSessionLeave,
                    Effect.FinishSession(
                        s.pkg,
                        endedAt = s.leftAt,
                        overran = s.extensionCount > 0,
                    ),
                    Effect.RefreshNotification,
                )
            }
            else -> emptyList()
        }
    }

    fun onSessionLeaveExpired(pkg: String, now: Long): List<Effect> {
        val s = state as? State.SessionLeaving ?: return emptyList()
        if (s.pkg != pkg) return emptyList()
        state = State.Background(null)
        return listOf(
            Effect.FinishSession(
                s.pkg,
                endedAt = s.leftAt,
                overran = s.extensionCount > 0,
            ),
            Effect.RefreshNotification,
        )
    }

    fun onTimeUpExtended(pkg: String, addMinutes: Int, now: Long): List<Effect> {
        val s = state as? State.TimeUp ?: return emptyList()
        if (s.pkg != pkg || s.extensionCount >= maxExtensions) return emptyList()
        state = State.InSession(
            pkg = pkg,
            startedAt = s.startedAt,
            plannedMinutes = s.plannedMinutes + addMinutes,
            extensionCount = s.extensionCount + 1,
            initialEntryPending = false,
        )
        return listOf(Effect.Dismiss, Effect.RefreshNotification)
    }

    fun onTimeUpExit(pkg: String, now: Long): List<Effect> {
        val s = state as? State.TimeUp ?: return emptyList()
        if (s.pkg != pkg) return emptyList()
        val overran = s.extensionCount > 0
        state = State.Background(null)
        return listOf(
            Effect.FinishSession(pkg, endedAt = now, overran = overran),
            Effect.RefreshNotification,
            Effect.SendHome(pkg),
        )
    }

    fun reset(now: Long): List<Effect> {
        val s = state
        state = State.Unknown
        return when (s) {
            is State.Prompting -> listOf(Effect.CancelPromptTimeout, Effect.Dismiss)
            is State.InSession -> listOf(
                Effect.FinishSession(s.pkg, endedAt = now, overran = s.extensionCount > 0),
                Effect.RefreshNotification,
            )
            is State.SessionLeaving -> listOf(
                Effect.CancelSessionLeave,
                Effect.FinishSession(s.pkg, endedAt = now, overran = s.extensionCount > 0),
                Effect.RefreshNotification,
            )
            is State.TimeUp -> {
                listOf(
                    Effect.FinishSession(s.pkg, endedAt = now, overran = s.extensionCount > 0),
                    Effect.Dismiss,
                    Effect.RefreshNotification,
                )
            }
            // 取消/超时已经进入 Background，但 Home 交接完成前 overlay 仍在。
            // 生命周期 reset 必须能收掉这层遮挡，不能只根据状态名推断没有窗口。
            else -> listOf(Effect.Dismiss)
        }
    }
}
