package me.excuse.app.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.excuse.app.data.UsageRepositoryContract
import me.excuse.app.overlay.InterceptOverlay
import me.excuse.app.overlay.OverlayManager
import me.excuse.app.overlay.TimeUpOverlay
import me.excuse.app.service.InterceptStateMachine.Effect
import me.excuse.app.strict.ReturnHomeActivity
import me.excuse.app.strict.StealForegroundActivity
import me.excuse.app.util.AppInfoUtil
import me.excuse.app.util.ReasonNormalizer
import me.excuse.app.util.TimeUtil

/**
 * Prompt 的完整呈现边界：预挂载、前台稳定、ready/fallback、generation 校验、提交和 overlay。
 * Service 只把 effect 和前台观察结果交进来，不再持有这套易竞争的时序状态。
 */
class PromptPresenter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: UsageRepositoryContract,
    private val stateMachine: InterceptStateMachine,
    private val sessionStore: SessionStore,
    private val monitoredPackages: () -> Set<String>,
    private val foregroundPackage: () -> String?,
    private val foregroundClassName: () -> String?,
    private val hasPendingPauseFor: (String) -> Boolean,
    private val executeEffects: (List<Effect>) -> Unit,
    private val beforeSendHome: (String, Long) -> Unit,
    private val afterReturnHome: (Long) -> Unit,
    private val debugLog: (String) -> Unit,
) {
    private val overlayManager = OverlayManager(context)
    private val appNameCache = HashMap<String, String>()
    private val reasonUseCountsCache = DailyReasonCountsCache(REASONS_CACHE_TTL_MS)
    private val reasonUseCountsStates = HashMap<String, MutableState<Map<String, Int>>>()
    private val reasonsRefreshJobs = HashMap<String, Pair<Long, Job>>()

    private var foregroundStealState = PromptForegroundStealState()
    private var generation: Long = 0L
    private var promptTimeoutJob: Job? = null
    private var homeReturnJob: Job? = null
    private val returningHome = mutableStateOf(false)

    fun prepareAndWarmUp() {
        if (!overlayManager.prepare()) return
        scope.launch {
            // 首轮初始化先走几帧；不可见真实结构预热 Compose/输入框，避免与首个 prompt 抢窗。
            delay(OVERLAY_WARM_UP_DELAY_MS)
            overlayManager.warmUp {
                InterceptOverlay(
                    promptKey = WARM_UP_PROMPT_KEY,
                    animateEntry = false,
                    appName = WARM_UP_APP_NAME,
                    reasonUseCountsToday = emptyMap(),
                    onConfirm = { _, _ -> },
                    onCancel = {},
                    onInteraction = {},
                )
            }
        }
    }

    fun cacheAppName(packageName: String, appName: String) {
        appNameCache[packageName] = appName
    }

    fun appNameFor(packageName: String): String =
        appNameCache[packageName]
            ?: AppInfoUtil.appLabel(context, packageName).also { appNameCache[packageName] = it }

    fun recordInterceptOutcome(packageName: String, outcome: InterceptOutcome) {
        val appName = appNameFor(packageName)
        scope.launch(Dispatchers.IO) {
            try {
                repository.recordInterceptOutcome(packageName, appName, outcome)
            } catch (_: Exception) {}
        }
    }

    fun showPrompt(pkg: String) {
        homeReturnJob?.cancel()
        homeReturnJob = null
        returningHome.value = false
        // token 在异步 foreground-ready/fallback 启动前就推进。否则 A₁→B→A₂ 时，
        // A₁ 的旧回调只看包名会再次变成“当前”，把 A₂ 的表单重建一遍。
        val request = PromptSubmission(packageName = pkg, generation = ++generation)
        val decision = decidePromptForegroundSteal(foregroundStealState, Effect.ShowPrompt(pkg))
        foregroundStealState = decision.nextState
        stealForegroundAndShowPrompt(request, decision.shouldSteal)
    }

    fun dismiss(immediately: Boolean = false) {
        homeReturnJob?.cancel()
        homeReturnJob = null
        generation += 1L
        foregroundStealState =
            decidePromptForegroundSteal(foregroundStealState, Effect.Dismiss).nextState
        overlayManager.dismiss(immediately)
    }

    fun sendHome(targetPackage: String) {
        homeReturnJob?.cancel()
        returningHome.value = true
        val requestGeneration = generation
        val completeReturn = fun(succeeded: Boolean) {
            if (!isCurrentGeneration(requestGeneration, generation)) return
            homeReturnJob?.cancel()
            homeReturnJob = null
            debugLog("home return completed success=$succeeded generation=$requestGeneration")
            val exposedPackage = foregroundPackage()?.takeIf { it in monitoredPackages() }
                ?: targetPackage
            afterReturnHome(System.currentTimeMillis())
            // 挂窗失败兜底仍是未回答的 Prompting，由原 timeout 合法结束它。
            if (stateMachine.state is InterceptStateMachine.State.Prompting) {
                returningHome.value = false
                return
            }
            if (succeeded) {
                // 先等桌面盖住退出页再淡出，避免露出系统正在缩小的空白过渡卡片。
                dismiss()
            } else {
                // OEM 拒绝/漏回调时重新给出选择，不能把失效的 HOME 当成通行许可。
                val effects = stateMachine.tick(
                    foreground = exposedPackage,
                    monitored = monitoredPackages(),
                    now = System.currentTimeMillis(),
                    entryEdge = true,
                )
                if (effects.isEmpty()) dismiss() else executeEffects(effects)
            }
        }
        val homeReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            @Suppress("DEPRECATION")
            override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) {
                if (resultCode == ReturnHomeActivity.RESULT_COVER_READY) {
                    val commandReceiver = resultData?.getParcelable<ResultReceiver>(
                        ReturnHomeActivity.EXTRA_COMMAND_RECEIVER,
                    ) ?: return
                    val current = isCurrentGeneration(requestGeneration, generation) &&
                        homeReturnJob?.isActive == true
                    if (current) {
                        // 超时、关屏或新 prompt 取消等待时也要结束旧退出页，否则它可能
                        // 留在前台，并让下一次 singleInstance 启动复用已失效的回调。
                        homeReturnJob?.invokeOnCompletion {
                            commandReceiver.send(
                                ReturnHomeActivity.COMMAND_CANCEL,
                                android.os.Bundle.EMPTY,
                            )
                        }
                    }
                    // 焦点交给退出页，触摸遮挡则保留到 Home 交接完成。
                    // dismiss 会立刻把触摸穿透，恰好让用户打断 Home 动画并重进目标。
                    val focusReleased = current && overlayManager.releaseFocusForHome()
                    commandReceiver.send(
                        if (focusReleased) ReturnHomeActivity.COMMAND_RETURN_HOME
                        else ReturnHomeActivity.COMMAND_CANCEL,
                        android.os.Bundle.EMPTY,
                    )
                    if (current && !focusReleased) completeReturn(false)
                    return
                }
                completeReturn(resultCode == ReturnHomeActivity.RESULT_HOME_FINISHED)
            }
        }
        homeReturnJob = scope.launch {
            delay(HOME_RETURN_TIMEOUT_MS)
            completeReturn(false)
        }
        val observed = foregroundPackage()
        val observedClass = foregroundClassName()
        val requestPlan = planHomeRequest(
            targetPackage = targetPackage,
            observedForegroundPackage = observed,
            observedForegroundClassName = observedClass,
            hasPendingPauseFor = hasPendingPauseFor,
            isMonitored = { it in monitoredPackages() },
            stabilizerPackage = context.packageName,
            stabilizerClassName = StealForegroundActivity::class.java.name,
        )
        val home = Intent(context, ReturnHomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra(ReturnHomeActivity.EXTRA_RESULT_RECEIVER, homeReceiver)
        }
        // 先捕获调用边沿、启动成功后记账。退出桥页的实际收尾会结束 guard，
        // 不把 startActivity 返回等同于已经回到桌面。
        val homeRequestAt = System.currentTimeMillis()
        try {
            context.startActivity(home)
            beforeSendHome(requestPlan.guardPackage ?: targetPackage, homeRequestAt)
        } catch (error: Exception) {
            debugLog("sendHome failed target=$targetPackage error=${error.javaClass.simpleName}")
            completeReturn(false)
        }
    }

    fun schedulePromptTimeout(pkg: String, durationMs: Long) {
        promptTimeoutJob?.cancel()
        val request = PromptSubmission(packageName = pkg, generation = generation)
        promptTimeoutJob = scope.launch {
            delay(durationMs)
            if (isCurrent(request)) {
                executeEffects(stateMachine.onPromptTimedOut(pkg, System.currentTimeMillis()))
            }
        }
    }

    fun cancelPromptTimeout() {
        promptTimeoutJob?.cancel()
        promptTimeoutJob = null
    }

    fun pause() {
        // 取消后的 Home 交接仍可能持有可触摸窗口，即使状态机已在 Background。
        dismiss(immediately = true)
        promptTimeoutJob?.cancel()
        reasonsRefreshJobs.values.forEach { it.second.cancel() }
        reasonsRefreshJobs.clear()
    }

    fun release() {
        pause()
        overlayManager.release()
    }

    fun showTimeUp(pkg: String) {
        val session = sessionStore.get(pkg) ?: return
        returningHome.value = false
        val extendingState = mutableStateOf(false)
        val actualMinutes =
            ((System.currentTimeMillis() - session.startedAt) / 60_000L)
                .toInt()
                .coerceAtLeast(session.plannedMinutes)
        val shown = overlayManager.show {
            TimeUpOverlay(
                appName = session.appName,
                originalReason = session.reason,
                actualMinutes = actualMinutes,
                plannedMinutes = session.plannedMinutes,
                canExtend = session.extensionCount < 1,
                returningHome = returningHome.value,
                extending = extendingState.value,
                onExtend = {
                    if (returningHome.value || extendingState.value) return@TimeUpOverlay
                    when (val attempt = requestTimeUpExtension(
                        stateMachine = stateMachine,
                        sessionStore = sessionStore,
                        pkg = pkg,
                        addMinutes = TIME_UP_EXTENSION_MINUTES,
                        now = System.currentTimeMillis(),
                        onElapsed = {
                            executeEffects(
                                stateMachine.onSessionTimeElapsed(pkg, System.currentTimeMillis())
                            )
                        },
                    )) {
                        is TimeUpExtensionAttempt.Applied -> {
                            extendingState.value = true
                            executeEffects(attempt.effects)
                        }
                        is TimeUpExtensionAttempt.TimerMissing -> {
                            debugLog("time-up extension rejected: missing timer pkg=$pkg")
                            executeEffects(stateMachine.reset(System.currentTimeMillis()))
                            sendHome(pkg)
                        }
                        TimeUpExtensionAttempt.Rejected -> Unit
                    }
                },
                onExit = {
                    if (!returningHome.value && !extendingState.value) {
                        executeEffects(stateMachine.onTimeUpExit(pkg, System.currentTimeMillis()))
                    }
                },
            )
        }
        if (!shown) {
            debugLog("time-up overlay show failed pkg=$pkg")
            executeEffects(stateMachine.reset(System.currentTimeMillis()))
            sendHome(pkg)
        }
    }

    private fun stealForegroundAndShowPrompt(
        request: PromptSubmission,
        shouldSteal: Boolean,
    ) {
        val pkg = request.packageName
        val overlayReady = overlayManager.prepare()
        if (shouldSteal && overlayReady) {
            var readyState = ReadyOrFallbackState()
            val presentPrompt = fun() {
                val current = isCurrent(request)
                val decision = decideReadyOrFallback(readyState, current)
                readyState = decision.nextState
                if (decision.shouldPresent) showPromptOverlay(request)
            }
            val readyReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) {
                    if (resultCode == StealForegroundActivity.RESULT_FOREGROUND_READY) presentPrompt()
                }
            }
            val intent = Intent(context, StealForegroundActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra(StealForegroundActivity.EXTRA_READY_RECEIVER, readyReceiver)
            }
            try {
                // SYSTEM_ALERT_WINDOW 是 Android 10–15 的直接 Activity 启动例外；OEM 仍可能拒绝，
                // 所以 fallback 始终保留，且两条回调由一次性 gate 仲裁。
                context.startActivity(intent)
                debugLog("foreground steal requested pkg=$pkg")
                scope.launch {
                    delay(FOREGROUND_READY_FALLBACK_MS)
                    presentPrompt()
                }
                return
            } catch (e: Exception) {
                debugLog("foreground steal failed pkg=$pkg error=${e.javaClass.simpleName}")
            }
        } else if (shouldSteal) {
            debugLog("foreground steal skipped: overlay not ready pkg=$pkg")
        }
        if (isCurrent(request)) showPromptOverlay(request)
    }

    private fun showPromptOverlay(
        submission: PromptSubmission,
        retryAfterFailure: Boolean = true,
    ) {
        if (!isCurrent(submission)) return
        val pkg = submission.packageName
        val appName = appNameFor(pkg)
        val reasonUseCountsState = refreshReasonUseCounts(pkg)
        val submittingState = mutableStateOf(false)

        val animateEntry = !overlayManager.isShowing()
        val shown = overlayManager.show {
            InterceptOverlay(
                promptKey = pkg,
                animateEntry = animateEntry,
                appName = appName,
                reasonUseCountsToday = reasonUseCountsState.value,
                submitting = submittingState.value,
                returningHome = returningHome.value,
                onInteraction = {
                    if (!submittingState.value && !returningHome.value && isCurrent(submission)) {
                        // 用户跨午夜继续输入时也刷新日期，不为这条提示增加常驻轮询。
                        if (reasonsRefreshJobs[pkg]?.first != TimeUtil.startOfTodayMillis()) {
                            refreshReasonUseCounts(pkg)
                        }
                        executeEffects(
                            stateMachine.onPromptInteraction(pkg, System.currentTimeMillis())
                        )
                    }
                },
                onConfirm = { reason, minutes ->
                    if (!submittingState.value && !returningHome.value && isCurrent(submission)) {
                        submittingState.value = true
                        executeEffects(listOf(Effect.CancelPromptTimeout))
                        scope.launch {
                            handleConfirm(submission, appName, reason, minutes, submittingState)
                        }
                    }
                },
                onCancel = {
                    if (!submittingState.value && !returningHome.value && isCurrent(submission)) {
                        executeEffects(stateMachine.onPromptCancelled(pkg, System.currentTimeMillis()))
                    }
                },
            )
        }
        if (!shown) {
            debugLog("overlay show failed pkg=$pkg")
            if (retryAfterFailure) {
                // WindowManager 已同步重建过一次；先让窗口系统短暂稳定后再做最后一次
                // 呈现尝试。若先 Home 再重试，成功时首帧会落在 launcher 上，反而像 ghost。
                scope.launch {
                    delay(FOREGROUND_READY_FALLBACK_MS)
                    if (isCurrent(submission)) {
                        showPromptOverlay(submission, retryAfterFailure = false)
                    }
                }
            } else {
                debugLog("overlay retry exhausted pkg=$pkg")
                // 两轮窗口重建仍失败时先保证目标 app 不会裸露；原 prompt timeout 继续
                // 作为合法终态，避免另造一个会破坏三出口不变式的 presentation-failed 状态。
                sendHome(pkg)
            }
        }
    }

    private fun isCurrent(submission: PromptSubmission): Boolean =
        isPromptSubmissionCurrent(
            submission = submission,
            currentGeneration = generation,
            currentPromptPackage = currentPromptPackage(),
        )

    private fun currentPromptPackage(): String? =
        (stateMachine.state as? InterceptStateMachine.State.Prompting)?.pkg

    private suspend fun handleConfirm(
        submission: PromptSubmission,
        appName: String,
        reason: String,
        plannedMinutes: Int,
        submittingState: MutableState<Boolean>,
    ) {
        val pkg = submission.packageName
        var insertedSession: SessionStore.Session? = null
        var sessionStarted = false
        try {
            if (!isCurrent(submission)) return
            val session = try {
                sessionStore.start(pkg, appName, reason, plannedMinutes) {
                    executeEffects(stateMachine.onSessionTimeElapsed(pkg, System.currentTimeMillis()))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(submission)) {
                    executeEffects(stateMachine.onPromptCancelled(pkg, System.currentTimeMillis()))
                }
                return
            }
            insertedSession = session
            rememberReasonUse(pkg, reason)

            if (!isCurrent(submission)) {
                finishStoredSessionNow(pkg, overran = false)
                insertedSession = null
                return
            }

            val latestMonitored = monitoredPackages()
            val confirmationPlan = planPromptConfirmation(
                targetPackage = pkg,
                observedForegroundPackage = foregroundPackage(),
                monitored = latestMonitored,
            )
            val confirmEffects =
                stateMachine.onPromptConfirmed(pkg, plannedMinutes, System.currentTimeMillis())
            if (confirmEffects.isEmpty()) {
                finishStoredSessionNow(pkg, overran = false)
                insertedSession = null
                return
            }

            sessionStarted = true
            executeEffects(confirmEffects)
            if (confirmationPlan.shouldFinishRemovedSession) {
                // DB start 挂起期间用户可能已在名单页移除 A。先用“确认”合法结束
                // Prompting，再立刻让 InSession 的配置移除分支收尾；绝不重启已移除目标。
                executeEffects(
                    stateMachine.tick(
                        foreground = null,
                        monitored = latestMonitored,
                        now = System.currentTimeMillis(),
                    )
                )
                return
            }
            if (confirmationPlan.shouldLaunchTarget && !launchTargetApp(pkg)) {
                executeEffects(stateMachine.reset(System.currentTimeMillis()))
                sessionStarted = false
                insertedSession = null
                sendHome(pkg)
            }
        } catch (cancelled: CancellationException) {
            if (!sessionStarted) {
                insertedSession?.let {
                    withContext(NonCancellable) { finishStoredSessionNow(pkg, overran = false) }
                }
            }
            throw cancelled
        } finally {
            if (isCurrent(submission)) submittingState.value = false
        }
    }

    private suspend fun finishStoredSessionNow(pkg: String, overran: Boolean) {
        try {
            sessionStore.finishNow(pkg, System.currentTimeMillis(), overran)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {}
    }

    private fun rememberReasonUse(pkg: String, reason: String) {
        val normalized = ReasonNormalizer.normalize(reason)
        if (normalized.isEmpty()) return
        val updated = reasonUseCountsCache.recordUse(
            pkg, normalized, TimeUtil.startOfTodayMillis(), System.currentTimeMillis(),
        )
        reasonUseCountsStates[pkg]?.value = updated
    }

    private fun refreshReasonUseCounts(pkg: String): MutableState<Map<String, Int>> {
        val dayStart = TimeUtil.startOfTodayMillis()
        val cached = reasonUseCountsCache.get(pkg, dayStart)
        val state = reasonUseCountsStates.getOrPut(pkg) { mutableStateOf(emptyMap()) }
        state.value = cached?.counts.orEmpty()
        if (!reasonUseCountsCache.needsRefresh(cached, System.currentTimeMillis())) return state

        val running = reasonsRefreshJobs[pkg]
        if (running?.first == dayStart && running.second.isActive) return state
        running?.second?.cancel()
        // 按 package 和查询日期隔离任务；午夜前尚未返回的结果不能占住今天的刷新入口。
        reasonsRefreshJobs[pkg] = dayStart to scope.launch {
            val fresh = try {
                withContext(Dispatchers.IO) { repository.reasonUseCountsTodayFor(pkg) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (fresh != null) {
                reasonUseCountsCache.completeRefresh(
                    pkg, cached, dayStart, TimeUtil.startOfTodayMillis(),
                    System.currentTimeMillis(), fresh,
                )?.let { state.value = it }
            }
        }
        return state
    }

    private fun launchTargetApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val OVERLAY_WARM_UP_DELAY_MS = 64L
        private const val FOREGROUND_READY_FALLBACK_MS = 120L
        private const val HOME_RETURN_TIMEOUT_MS = 1_500L
        private const val WARM_UP_PROMPT_KEY = "__warm_up__"
        private const val WARM_UP_APP_NAME = "应用"
        private const val REASONS_CACHE_TTL_MS = 60_000L
        private const val TIME_UP_EXTENSION_MINUTES = 5
    }
}
