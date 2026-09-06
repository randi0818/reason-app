package me.excuse.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.excuse.app.AppServices
import me.excuse.app.BuildConfig
import me.excuse.app.MainActivity
import me.excuse.app.R
import me.excuse.app.service.InterceptStateMachine.Effect

/** 把 UsageEvents 解释成 sticky 前台并执行状态机 effect；窗口竞争交给 [PromptPresenter]。 */
class AppMonitorService : LifecycleService() {

    private data class UsageEventSnapshot(
        val timestamp: Long,
        val eventType: Int,
        val packageName: String,
        val className: String?,
    )

    private data class EventKey(
        val timestamp: Long,
        val eventType: Int,
        val packageName: String,
        val className: String?,
    )

    private data class StartupHistoryResult(
        val packageName: String?,
        val querySucceeded: Boolean,
    )

    private lateinit var sessionStore: SessionStore
    private lateinit var promptPresenter: PromptPresenter
    private lateinit var usm: UsageStatsManager
    private lateinit var permissionObserver: MonitoringPermissionObserver
    private val sessionWriterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var startupCleanupJob: Job
    private val monitoredReady = CompletableDeferred<Unit>()
    private var pollJob: Job? = null
    private var notifJob: Job? = null
    private var stoppingForMissingPermissions = false
    private var startupSeedAttempted = false

    @Volatile private var monitoredCache: Set<String> = emptySet()
    private var monitoredSubscription: Job? = null

    private var screenReceiver: BroadcastReceiver? = null

    private val stateMachine = InterceptStateMachine()
    private val foregroundInterpreter = ForegroundEventInterpreter(PAUSE_REENTRY_GAP_MS)
    private val postHomeEventGuard = PostHomeEventGuard(POST_HOME_USAGE_STATS_SUPPRESS_MS)
    private var lastQueryTime = 0L
    private var lastUsageStatsCheckAt = 0L
    private val seenEvents = LinkedHashMap<EventKey, Long>()
    private var sessionLeaveJob: Job? = null
    private var sessionLeaveGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        val app = application as AppServices
        sessionStore = SessionStore(
            timer = SessionTimer(lifecycleScope),
            writer = app.repository,
            writerScope = sessionWriterScope,
            onWriteFailure = { operation, error ->
                Log.e(DIAG_TAG, "$operation failed; queued for bounded retry", error)
            },
        )
        promptPresenter = PromptPresenter(
            context = this,
            scope = lifecycleScope,
            repository = app.repository,
            stateMachine = stateMachine,
            sessionStore = sessionStore,
            monitoredPackages = { monitoredCache },
            foregroundPackage = { foregroundInterpreter.stickyPackageName },
            foregroundClassName = { foregroundInterpreter.stickyClassName },
            hasPendingPauseFor = { foregroundInterpreter.hasPendingPauseFor(it) },
            executeEffects = { execute(it) },
            beforeSendHome = { targetPackage, now ->
                debugLog {
                    "sendHome state=${stateMachine.state} ${foregroundInterpreter.debugState()}"
                }
                foregroundInterpreter.forceForeground(null, observedAt = now)
                postHomeEventGuard.markHome(targetPackage, now)
            },
            afterReturnHome = { now ->
                foregroundInterpreter.forceForeground(null, observedAt = now)
                postHomeEventGuard.completeHome(now)
            },
            debugLog = { message -> debugLog { message } },
        )
        usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        startInForeground()

        startupCleanupJob = app.sessionCleanupQueue.enqueue("unfinished-session recovery") {
            app.repository.finishUnfinishedSessions()
        }

        try { promptPresenter.prepareAndWarmUp() } catch (_: Exception) {}

        monitoredSubscription = lifecycleScope.launch {
            try {
                app.repository.monitoredApps().collect { list ->
                    val enabledApps = list.filter { it.enabled }
                    // monitoredCache 一旦发布，下一次轮询就可能马上触发 prompt；先填名字，
                    // 避免首个 prompt 在主线程冷调 PackageManager。
                    enabledApps.forEach { monitored ->
                        promptPresenter.cacheAppName(monitored.packageName, monitored.appName)
                    }
                    val nextMonitored = enabledApps.map { it.packageName }.toSet()
                    val removed = monitoredCache - nextMonitored
                    monitoredCache = nextMonitored
                    monitoredReady.complete(Unit)
                    val activePackage = when (val state = stateMachine.state) {
                        is InterceptStateMachine.State.InSession -> state.pkg
                        is InterceptStateMachine.State.SessionLeaving -> state.pkg
                        is InterceptStateMachine.State.TimeUp -> state.pkg
                        else -> null
                    }
                    // 只主动处理“移除正在运行的目标”。新增 app 时 sticky 可能仍是进入
                    // Reason 主界面前的旧值，拿它主动 tick 会在设置页制造幽灵 prompt。
                    // 这里传 null 只结束旧 session；真实的新前台仍交给 UsageEvents。
                    if (activePackage != null && activePackage in removed) {
                        executeTick(
                            foreground = null,
                            now = System.currentTimeMillis(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(DIAG_TAG, "monitored-app subscription failed", error)
            } finally {
                monitoredReady.complete(Unit)
            }
        }

        permissionObserver = MonitoringPermissionObserver(this) {
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                ensureMonitoringPermissionsOrStop()
            }
        }.also { it.start() }

        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> pauseDetection()
                        Intent.ACTION_SCREEN_ON -> resumeDetection()
                    }
                }
            }
            screenReceiver = receiver
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!ensureMonitoringPermissionsOrStop()) return START_NOT_STICKY
        val pm = getSystemService(PowerManager::class.java)
        val interactive = pm?.isInteractive ?: true
        if (interactive) {
            resumeDetection()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        if (::permissionObserver.isInitialized) permissionObserver.stop()
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
        monitoredSubscription?.cancel()
        pollJob?.cancel()
        notifJob?.cancel()
        // 禁止挂起的确认在快照之后再启动计时器；后台收尾还会等其取消清理真正结束。
        lifecycleScope.cancel()
        finishActiveSessionsForShutdown()
        sessionWriterScope.cancel()
        promptPresenter.release()
        super.onDestroy()
    }

    private fun resumeDetection() {
        if (!ensureMonitoringPermissionsOrStop()) return
        if (pollJob?.isActive != true) {
            pollJob = lifecycleScope.launch { pollLoop() }
        }
        if (notifJob?.isActive != true) {
            notifJob = lifecycleScope.launch { notificationTickLoop() }
        }
    }

    private fun pauseDetection() {
        val now = System.currentTimeMillis()
        execute(stateMachine.reset(now))
        promptPresenter.pause()
        foregroundInterpreter.reset()
        postHomeEventGuard.reset()
        seenEvents.clear()
        pollJob?.cancel()
        pollJob = null
        notifJob?.cancel()
        notifJob = null
    }

    private suspend fun notificationTickLoop() {
        while (currentCoroutineActive()) {
            if (!ensureMonitoringPermissionsOrStop()) return
            val hasActiveSession = sessionStore.all().isNotEmpty()
            if (hasActiveSession) {
                refreshNotification()
            }
            val nextTickMs = if (hasActiveSession) {
                NOTIFICATION_ACTIVE_TICK_MS
            } else {
                NOTIFICATION_IDLE_TICK_MS
            }
            delay(nextTickMs)
        }
    }

    private fun refreshNotification() {
        val now = System.currentTimeMillis()
        val nearest = sessionStore.all()
            .filter { (it.startedAt + it.plannedMinutes * 60_000L) > now }
            .minByOrNull { it.startedAt + it.plannedMinutes * 60_000L }

        val text = if (nearest != null) {
            val remainMs = (nearest.startedAt + nearest.plannedMinutes * 60_000L) - now
            val remainMin = ((remainMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            getString(R.string.notification_active_text, remainMin)
        } else {
            getString(R.string.notification_text)
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private suspend fun pollLoop() {
        // Room cleanup 和 monitored Flow 首帧都完成后才允许接收确认；否则清理 UPDATE
        // 可能误伤刚插入的新 session，seed 也可能在空名单下把真实前台吃掉。
        startupCleanupJob.join()
        monitoredReady.await()
        if (!ensureMonitoringPermissionsOrStop()) return

        // Seed phase：仅用过去 10 秒的事件初始化 sticky，并把这些事件登记到 seenEvents
        // 防止主循环 overlap 把它们当成新事件再处理（E1/E2 幽灵的来源之一）。
        val initNow = System.currentTimeMillis()
        val isFirstPollForService = !startupSeedAttempted
        // screen-off 可能在跨进程 query 中途取消当前协程；先落闸，下一次亮屏只做 10s seed。
        startupSeedAttempted = true
        lastQueryTime = initNow - SEED_WINDOW_MS
        var seedQueryFailed = false
        try {
            queryUsageEvents(lastQueryTime, initNow + 1_000L)
                .sortedWith(eventOrder)
                .forEach { event ->
                    foregroundInterpreter.seed(event.toForegroundEvent())
                    seenEvents[
                        EventKey(
                            event.timestamp,
                            event.eventType,
                            event.packageName,
                            event.className,
                        )
                    ] = initNow
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 主循环下一轮仍会从 lastQueryTime overlap 重试；一次瞬时 binder 异常
            // 不能让整个 pollJob 永久退出。
            Log.e(DIAG_TAG, "foreground seed failed", error)
            foregroundInterpreter.reset()
            seenEvents.clear()
            seedQueryFailed = true
        }
        val seededForeground = foregroundInterpreter.stickyPackageName
        val shouldRecoverHistory = shouldAttemptStartupHistoryRecovery(
            seededPackage = seededForeground,
            isFirstPollForService = isFirstPollForService,
        )
        val historyResult = if (shouldRecoverHistory) {
            recoverStartupForegroundFromEvents(initNow)
        } else {
            StartupHistoryResult(packageName = null, querySucceeded = false)
        }
        val startupDecision = decideStartupForeground(
            seededPackage = seededForeground,
            historyPackage = historyResult.packageName,
        )
        // Seed 失败时保留完整回溯窗口，让正常主循环的下一次 query 再试一次；
        // 成功时仍沿用原来的 300ms overlap，避免重复解释初始化事件。
        lastQueryTime = if (seedQueryFailed && !historyResult.querySucceeded) {
            initNow - SEED_WINDOW_MS
        } else {
            initNow
        }
        executeTick(
            foreground = startupDecision.packageName,
            now = initNow,
            entryEdge = startupDecision.recoveredFromHistory &&
                startupDecision.packageName in monitoredCache,
        )

        while (currentCoroutineActive()) {
            try {
                val now = System.currentTimeMillis()
                val monitored = monitoredCache

                if (monitored.isEmpty() &&
                    sessionStore.all().isEmpty() &&
                    stateMachine.state !is InterceptStateMachine.State.Prompting
                ) {
                    foregroundInterpreter.reset()
                    seenEvents.clear()
                    lastQueryTime = now
                    delay(POLL_NO_MONITORED_TICK_MS)
                    continue
                }

                val queryStart = lastQueryTime - 300L
                val events = queryUsageEvents(queryStart, now + 1000L)
                    .sortedWith(eventOrder)
                // 查询挂起时取消/退出回调可能推进 Home barrier。解释返回的事件必须用
                // 返回后的观察时刻，否则旧 now < barrier 会被误判成“系统时钟回拨”。
                val observedNow = System.currentTimeMillis()
                lastQueryTime = now
                pruneSeenEvents(now)

                events.forEach { ev ->
                    if (ev.eventType !in trackedEventTypes) return@forEach
                    val key = EventKey(ev.timestamp, ev.eventType, ev.packageName, ev.className)
                    if (seenEvents.put(key, now) != null) return@forEach
                    if (postHomeEventGuard.shouldDropStaleResume(
                            isResume = ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED,
                            eventTimestamp = ev.timestamp,
                            packageName = ev.packageName,
                            monitored = monitored,
                            now = observedNow,
                        )
                    ) {
                        debugLog {
                            "drop stale post-home RESUMED pkg=${ev.packageName} ts=${ev.timestamp}"
                        }
                        return@forEach
                    }
                    processEvent(ev, observedNow)
                }

                // PAUSED 没等到 RESUMED 跟来：超出 200ms 视为真离开
                maybeAgePendingPause(observedNow)

                // 兜底：事件流如果错过了 RESUMED（部分 ROM / 某些场景下），sticky 会卡在 null。
                // 查 UsageStats 看看 OS 当前认为前台是什么，必要时补一脚状态机。
                maybeReconcileWithUsageStats(observedNow)
            } catch (t: Exception) {
                debugLog { "poll exception=${t::class.java.simpleName}: ${t.message}" }
            }
            delay(pollDelayMs())
        }
    }

    private fun processEvent(ev: UsageEventSnapshot, now: Long) {
        debugLog {
            "event ${eventName(ev.eventType)} pkg=${ev.packageName} cls=${ev.className} " +
                "ts=${ev.timestamp} ${foregroundInterpreter.debugState()}"
        }
        foregroundInterpreter.process(ev.toForegroundEvent(), now).forEach { change ->
            executeTick(change.packageName, change.at, change.isEntryEdge)
        }
    }

    private fun eventName(eventType: Int): String = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> "RESUMED"
        UsageEvents.Event.ACTIVITY_PAUSED -> "PAUSED"
        UsageEvents.Event.ACTIVITY_STOPPED -> "STOPPED"
        else -> eventType.toString()
    }

    private fun UsageEventSnapshot.toForegroundEvent(): ForegroundEventInterpreter.Event {
        val type = when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundEventInterpreter.EventType.Resumed
            UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundEventInterpreter.EventType.Paused
            UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundEventInterpreter.EventType.Stopped
            else -> error("Unsupported foreground event type: $eventType")
        }
        return ForegroundEventInterpreter.Event(
            timestamp = timestamp,
            eventType = type,
            packageName = packageName,
            className = className,
        )
    }

    /**
     * 当事件流漏发 RESUMED 导致 sticky 未知或停在 launcher/稳定页时，查 UsageStats
     * 获取带时间戳的候选；只有它比最近事件新且符合当前状态，才拨 sticky 并补 tick。
     *
     * 节流到至少 [USAGE_STATS_CHECK_MIN_INTERVAL_MS] 一次，避免每个 poll 周期都跨进程查。
     */
    private suspend fun maybeReconcileWithUsageStats(now: Long) {
        val sticky = foregroundInterpreter.stickyPackageName
        val state = stateMachine.state
        val stickyIsMonitored = sticky != null && sticky in monitoredCache
        val waitingForInitialSessionEntry =
            state is InterceptStateMachine.State.InSession && state.initialEntryPending
        val reconcilableState =
            state is InterceptStateMachine.State.Background ||
                state is InterceptStateMachine.State.Unknown ||
                state is InterceptStateMachine.State.SessionLeaving ||
                waitingForInitialSessionEntry
        if (!reconcilableState || stickyIsMonitored) return
        if (postHomeEventGuard.shouldSkipUsageStatsReconcile(now)) return
        if (now >= lastUsageStatsCheckAt &&
            now - lastUsageStatsCheckAt < USAGE_STATS_CHECK_MIN_INTERVAL_MS
        ) return
        if (monitoredCache.isEmpty()) return
        lastUsageStatsCheckAt = now
        val candidate = queryCurrentForegroundFromStats(now) ?: return
        val applyNow = System.currentTimeMillis()
        // binder 查询本身也可能卡住；返回时已经跨出 freshness/leave deadline 的快照
        // 不能再按查询前的 now 应用，否则会把久离误当成宽限内重入。
        if (candidate.lastTimeUsed <= applyNow - USAGE_STATS_FRESHNESS_MS) return
        // queryUsageStats 在后台线程运行；回来时 prompt/session 可能已由别的主线程回调推进。
        // 用最新状态和最新事件证据做最终仲裁，不能拿查询前的快照覆盖新状态。
        val currentState = stateMachine.state
        val currentSticky = foregroundInterpreter.stickyPackageName
        val shouldApply = shouldApplyUsageStatsForeground(
            state = currentState,
            stickyPackage = currentSticky,
            lastForegroundChangeAt = foregroundInterpreter.lastForegroundChangeAt,
            candidate = candidate,
            monitored = monitoredCache,
        )
        if (!shouldApply) return
        val realFg = candidate.packageName
        val realFgIsMonitored = realFg in monitoredCache
        debugLog {
            "reconcile from UsageStats: sticky=$currentSticky state=$currentState -> $candidate " +
                "realFgIsMonitored=$realFgIsMonitored"
        }
        foregroundInterpreter.forceForeground(realFg, observedAt = candidate.lastTimeUsed)
        executeTick(realFg, applyNow, entryEdge = realFgIsMonitored)
    }

    private suspend fun queryCurrentForegroundFromStats(now: Long): UsageStatsForeground? =
        withContext(Dispatchers.Default) {
            val stats = try {
                usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    now - USAGE_STATS_FRESHNESS_MS,
                    now,
                )
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            stats
                .filter { it.packageName != null && it.lastTimeUsed > now - USAGE_STATS_FRESHNESS_MS }
                .maxByOrNull { it.lastTimeUsed }
                ?.let { UsageStatsForeground(it.packageName, it.lastTimeUsed) }
        }

    private suspend fun recoverStartupForegroundFromEvents(now: Long): StartupHistoryResult {
        // 只在 10 秒 seed 无法得出前台时回看一次。和 aggregate UsageStats 的“最近使用”
        // 不同，这里也会看到 launcher 的 RESUMED/PAUSED，停在桌面时不会把旧目标误判回来。
        try {
            queryUsageEvents(
                startupHistoryStartMillis(
                    nowMillis = now,
                    elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    recoveryWindowMillis = STARTUP_EVENT_RECOVERY_WINDOW_MS,
                ),
                now + 1_000L,
            )
                .sortedWith(eventOrder)
                .forEach { event ->
                    foregroundInterpreter.seed(event.toForegroundEvent())
                    if (event.timestamp >= now - SEED_WINDOW_MS) {
                        seenEvents[
                            EventKey(
                                event.timestamp,
                                event.eventType,
                                event.packageName,
                                event.className,
                            )
                        ] = now
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(DIAG_TAG, "foreground history recovery failed", error)
            foregroundInterpreter.reset()
            seenEvents.clear()
            return StartupHistoryResult(packageName = null, querySucceeded = false)
        }
        return StartupHistoryResult(
            packageName = foregroundInterpreter.stickyPackageName,
            querySucceeded = true,
        )
    }

    private fun maybeAgePendingPause(now: Long) {
        foregroundInterpreter.agePendingPause(now).forEach { change ->
            executeTick(change.packageName, change.at, change.isEntryEdge)
        }
    }

    private fun executeTick(foreground: String?, now: Long, entryEdge: Boolean = false) {
        val beforeState = stateMachine.state
        val beforeInitialEntryPending =
            (beforeState as? InterceptStateMachine.State.InSession)?.initialEntryPending
        val effects = stateMachine.tick(
            foreground = foreground,
            monitored = monitoredCache,
            now = now,
            entryEdge = entryEdge,
        )
        if (effects.isNotEmpty() || entryEdge) {
            val afterInitialEntryPending =
                (stateMachine.state as? InterceptStateMachine.State.InSession)?.initialEntryPending
            debugLog {
                "tick foreground=$foreground entryEdge=$entryEdge " +
                    "initialEntryPending=$beforeInitialEntryPending->$afterInitialEntryPending " +
                    "before=$beforeState state=${stateMachine.state} effects=$effects " +
                    foregroundInterpreter.debugState()
            }
        }
        execute(effects)
    }

    private fun execute(effects: List<Effect>) {
        effects.forEach { execute(it) }
    }

    private fun execute(effect: Effect) {
        when (effect) {
            is Effect.SendHome -> promptPresenter.sendHome(effect.pkg)
            Effect.Dismiss -> promptPresenter.dismiss()
            is Effect.ShowPrompt -> {
                if (ensureMonitoringPermissionsOrStop()) promptPresenter.showPrompt(effect.pkg)
            }
            is Effect.ShowTimeUp -> {
                if (ensureMonitoringPermissionsOrStop()) promptPresenter.showTimeUp(effect.pkg)
            }
            is Effect.FinishSession -> finishSession(effect.pkg, effect.endedAt, effect.overran)
            is Effect.RecordInterceptOutcome ->
                promptPresenter.recordInterceptOutcome(effect.pkg, effect.outcome)
            Effect.RefreshNotification -> refreshNotification()
            is Effect.SchedulePromptTimeout ->
                promptPresenter.schedulePromptTimeout(effect.pkg, effect.durationMs)
            Effect.CancelPromptTimeout -> promptPresenter.cancelPromptTimeout()
            is Effect.ScheduleSessionLeave -> scheduleSessionLeave(effect.pkg, effect.delayMs)
            Effect.CancelSessionLeave -> cancelSessionLeave()
        }
    }

    private fun scheduleSessionLeave(pkg: String, delayMs: Long) {
        sessionLeaveJob?.cancel()
        val requestGeneration = ++sessionLeaveGeneration
        sessionLeaveJob = lifecycleScope.launch {
            delay(delayMs)
            if (!isCurrentGeneration(requestGeneration, sessionLeaveGeneration)) return@launch
            sessionLeaveJob = null
            execute(stateMachine.onSessionLeaveExpired(pkg, System.currentTimeMillis()))
        }
    }

    private fun cancelSessionLeave() {
        sessionLeaveGeneration += 1L
        sessionLeaveJob?.cancel()
        sessionLeaveJob = null
    }

    private fun pollDelayMs(): Long =
        when (stateMachine.state) {
            is InterceptStateMachine.State.Prompting -> POLL_PROMPT_TICK_MS
            is InterceptStateMachine.State.InSession -> POLL_SESSION_TICK_MS
            is InterceptStateMachine.State.SessionLeaving -> POLL_SESSION_TICK_MS
            is InterceptStateMachine.State.TimeUp -> POLL_TIMEUP_TICK_MS
            is InterceptStateMachine.State.Background,
            is InterceptStateMachine.State.Unknown -> {
                if (monitoredCache.isEmpty()) POLL_NO_MONITORED_TICK_MS else POLL_IDLE_TICK_MS
            }
        }

    private suspend fun queryUsageEvents(startMillis: Long, endMillis: Long): List<UsageEventSnapshot> =
        withContext(Dispatchers.Default) {
            val events = usm.queryEvents(startMillis, endMillis)
            val ev = UsageEvents.Event()
            val result = ArrayList<UsageEventSnapshot>()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType !in trackedEventTypes) continue
                val packageName = ev.packageName ?: continue
                result.add(
                    UsageEventSnapshot(
                        timestamp = ev.timeStamp,
                        eventType = ev.eventType,
                        packageName = packageName,
                        className = ev.className,
                    )
                )
            }
            result
        }

    private fun pruneSeenEvents(now: Long) {
        val cutoff = now - SEEN_EVENT_TTL_MS
        val it = seenEvents.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove() else break
        }
    }

    private fun finishSession(pkg: String, endedAt: Long, overran: Boolean) =
        sessionStore.finish(pkg, endedAt, overran)

    private fun finishActiveSessionsForShutdown() {
        val stoppedStore = sessionStore
        stoppedStore.stopForShutdown(System.currentTimeMillis())
        val serviceJob = lifecycleScope.coroutineContext[Job]
        // 由 Application 持有，service 销毁不会取消冲刷；重启恢复也排在它后面。
        (application as AppServices).sessionCleanupQueue.enqueue("session shutdown flush") {
            serviceJob?.join()
            stoppedStore.flushForShutdown()
        }
    }

    private fun ensureMonitoringPermissionsOrStop(): Boolean {
        val ready = try {
            MonitoringPermissions.current(this).isReady
        } catch (error: RuntimeException) {
            Log.e(DIAG_TAG, "monitoring permission check failed", error)
            false
        }
        if (!ready) stopForMissingPermissions()
        return ready
    }

    private fun stopForMissingPermissions() {
        if (stoppingForMissingPermissions) return
        stoppingForMissingPermissions = true
        pauseDetection()
        stopSelf()
    }

    private suspend fun currentCoroutineActive(): Boolean = currentCoroutineContext().isActive

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(DIAG_TAG, message())
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setLocalOnly(true)
            .build()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_desc) }
        nm.createNotificationChannel(ch)

        val notification = buildNotification(getString(R.string.notification_text))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "monitor"
        private const val DIAG_TAG = "ReasonMonitor"
        // 每个 tick 都是一次跨进程 queryEvents（还带 1s 回溯窗口）。只有 IDLE 需要真的快 ——
        // 它决定「打开受监控 app 到弹出拦截窗」的延迟，是核心体验，保持 100ms。
        // 其余三个状态没有任何时间敏感的事要做：拦截窗 / 到点窗已经挂在屏幕上，session 期间
        // 只需要察觉离开，而离开本身还要再过 PAUSE_REENTRY_GAP_MS(200ms) 和 10s 宽限期。
        // 之前 30ms 意味着一个 30 分钟的 session 要打约 6 万次 binder，纯粹是白烧电。
        private const val POLL_PROMPT_TICK_MS = 100L
        private const val POLL_SESSION_TICK_MS = 150L
        private const val POLL_TIMEUP_TICK_MS = 100L
        private const val POLL_IDLE_TICK_MS = 100L
        private const val POLL_NO_MONITORED_TICK_MS = 1_000L
        private const val NOTIFICATION_ACTIVE_TICK_MS = 60_000L
        private const val NOTIFICATION_IDLE_TICK_MS = 60_000L
        private const val SEEN_EVENT_TTL_MS = 1_000L
        private const val SEED_WINDOW_MS = 10_000L
        // UsageStats can keep reporting the just-blocked app as "recent" for up to
        // USAGE_STATS_FRESHNESS_MS after we intentionally send the user Home.
        private const val POST_HOME_USAGE_STATS_SUPPRESS_MS = 6_000L
        /** PAUSED 后超过这个 gap 还没等到 RESUMED 跟来，就视为用户真的离开了。
         *  内含应用内跳页 / 系统瞬态 pause 的容忍度（通常 < 50ms），与人类 Home + 重进
         *  时间（通常 >= 300ms）有足够区分度。 */
        private const val PAUSE_REENTRY_GAP_MS = 200L

        /** UsageStats 兜底查询节流间隔。事件流正常时这条路基本不会走。 */
        private const val USAGE_STATS_CHECK_MIN_INTERVAL_MS = 500L

        /** 只信任 UsageStats 在最近 N 毫秒内有过 lastTimeUsed 更新的条目；
         *  防止把很久之前的"最后使用 app"误当成当前前台。 */
        private const val USAGE_STATS_FRESHNESS_MS = 5_000L

        /** 只在 service 冷启动且 10 秒 seed 无法得出前台时读取一次，不进入常态轮询。 */
        private const val STARTUP_EVENT_RECOVERY_WINDOW_MS = 24L * 60L * 60L * 1_000L

        private val trackedEventTypes = setOf(
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED,
        )

        // 同 ms 内排序：STOPPED 优先（直接清 sticky），其次 PAUSED（挂 pendingPause），最后 RESUMED
        // （更新 sticky，可能消化 pendingPause）。这样 C3 的"离开+回来"必然先发离开信号。
        private val eventOrder =
            compareBy<UsageEventSnapshot> { it.timestamp }
                .thenByDescending { it.eventType }

        fun start(context: Context) {
            val ready = try {
                MonitoringPermissions.current(context).isReady
            } catch (_: RuntimeException) {
                false
            }
            if (!ready) return
            val intent = Intent(context, AppMonitorService::class.java)
            context.startForegroundService(intent)
        }
    }
}
