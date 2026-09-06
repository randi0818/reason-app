package me.excuse.app.service

/**
 * Interprets UsageEvents-style activity lifecycle events into foreground package changes.
 *
 * The important ambiguity is a quick PAUSED -> RESUMED for the same package:
 *  - different class: usually in-app navigation, keep the current session alive;
 *  - same class: usually Home/Recents detour and immediate re-entry, so emit leave + re-entry.
 */
internal class ForegroundEventInterpreter(
    private val pauseReentryGapMs: Long,
    private val delayedStoppedGraceMs: Long = 1_000L,
) {

    enum class EventType {
        Resumed,
        Paused,
        Stopped,
    }

    data class Event(
        val timestamp: Long,
        val eventType: EventType,
        val packageName: String,
        val className: String?,
    )

    data class ForegroundChange(
        val packageName: String?,
        val at: Long,
        val isEntryEdge: Boolean = false,
    )

    private data class PendingPause(
        val pkg: String,
        val cls: String?,
        val at: Long,
    )

    private data class SuppressedStop(
        val pkg: String,
        val cls: String?,
        val until: Long,
    )

    var stickyPackageName: String? = null
        private set

    var stickyClassName: String? = null
        private set

    /** 最近一次已采纳的前台进入/离开证据时间；用于拒绝更旧的聚合 UsageStats。 */
    var lastForegroundChangeAt: Long? = null
        private set

    private var pendingPause: PendingPause? = null
    private var suppressedStop: SuppressedStop? = null

    fun reset() {
        stickyPackageName = null
        stickyClassName = null
        lastForegroundChangeAt = null
        pendingPause = null
        suppressedStop = null
    }

    fun seed(event: Event) {
        when (event.eventType) {
            EventType.Resumed -> {
                stickyPackageName = event.packageName
                stickyClassName = event.className
                recordEvidence(event.timestamp, event.timestamp)
                pendingPause = null
                suppressedStop = null
            }
            EventType.Paused,
            EventType.Stopped -> {
                if (matchesExactSticky(event)) {
                    stickyPackageName = null
                    stickyClassName = null
                    recordEvidence(event.timestamp, event.timestamp)
                    pendingPause = null
                    suppressedStop = null
                }
            }
        }
    }

    fun forceForeground(
        packageName: String?,
        className: String? = null,
        observedAt: Long? = null,
    ) {
        stickyPackageName = packageName
        stickyClassName = className
        if (observedAt == null) {
            lastForegroundChangeAt = null
        } else {
            recordEvidence(observedAt, observedAt)
        }
        pendingPause = null
        suppressedStop = null
    }

    fun process(event: Event, now: Long): List<ForegroundChange> {
        val evidenceWatermark = lastForegroundChangeAt
        if (evidenceWatermark != null &&
            event.timestamp < evidenceWatermark &&
            now >= evidenceWatermark
        ) {
            // 跨 query 迟到的旧生命周期不能推翻更新证据；同毫秒事件仍按 service
            // 的既有排序处理。若墙钟真的回拨（now < watermark），下面会重建水位。
            return emptyList()
        }
        return when (event.eventType) {
            EventType.Resumed -> handleResumed(event, now)
            EventType.Paused -> handlePaused(event)
            EventType.Stopped -> handleStopped(event, now)
        }
    }

    fun agePendingPause(now: Long): List<ForegroundChange> {
        val pp = pendingPause ?: return emptyList()
        if (now - pp.at <= pauseReentryGapMs) return emptyList()
        pendingPause = null
        if (stickyPackageName != pp.pkg) return emptyList()
        if (stickyClassName != null && stickyClassName != pp.cls) return emptyList()

        stickyPackageName = null
        stickyClassName = null
        val leftAt = pp.at + pauseReentryGapMs
        // 状态机的离开落点包含 200ms 消歧窗口，但可与 UsageStats 比较的明确证据
        // 仍是原始 PAUSED 时间。这样窗口内漏发的较新 RESUMED 仍有机会被兜底恢复。
        recordEvidence(pp.at, now)
        return listOf(ForegroundChange(null, leftAt))
    }

    /** PAUSED 尚在 200ms 消歧窗内时，sticky 不能当作“仍确定在这个 app”。 */
    fun hasPendingPauseFor(packageName: String): Boolean = pendingPause?.pkg == packageName

    fun debugState(): String =
        "sticky=($stickyPackageName, $stickyClassName) changedAt=$lastForegroundChangeAt " +
            "pp=$pendingPause suppressedStop=$suppressedStop"

    private fun handleResumed(event: Event, now: Long): List<ForegroundChange> {
        val changes = ArrayList<ForegroundChange>(2)
        var suppressEntryEdge = false

        pendingPause?.let { pp ->
            val gap = event.timestamp - pp.at
            val agedOut = gap > pauseReentryGapMs
            val sameActivityReturn = event.packageName == pp.pkg && event.className == pp.cls
            val inAppNavigation =
                !agedOut &&
                    event.packageName == pp.pkg &&
                    pp.cls != null &&
                    event.className != null &&
                    event.className != pp.cls

            if (agedOut || sameActivityReturn) {
                stickyPackageName = null
                stickyClassName = null
                if (sameActivityReturn) {
                    suppressedStop = SuppressedStop(
                        pkg = event.packageName,
                        cls = event.className,
                        until = event.timestamp + delayedStoppedGraceMs,
                    )
                }
                val leaveAt = if (agedOut) {
                    pp.at + pauseReentryGapMs
                } else {
                    eventTime(event, now)
                }
                changes += ForegroundChange(null, leaveAt)
            } else if (inAppNavigation) {
                suppressEntryEdge = true
            }
            pendingPause = null
        }

        val before = stickyPackageName
        val beforeClass = stickyClassName
        stickyPackageName = event.packageName
        stickyClassName = event.className
        recordEvidence(event.timestamp, now)
        if (before != event.packageName) {
            changes += ForegroundChange(
                packageName = event.packageName,
                at = eventTime(event, now),
                isEntryEdge = true,
            )
        } else if (!suppressEntryEdge &&
            (beforeClass == null || event.className == null || beforeClass == event.className)
        ) {
            // PAUSED 漏报时，明确的 Activity A→B 是正常应用内跳页；同 Activity 或
            // class 未知仍保留“离开再进入”的保守启发式，堵住 Home/Recents 快速返回。
            val at = eventTime(event, now)
            changes += ForegroundChange(null, at)
            changes += ForegroundChange(
                packageName = event.packageName,
                at = at,
                isEntryEdge = true,
            )
        }
        return changes
    }

    private fun handlePaused(event: Event): List<ForegroundChange> {
        if (matchesPausedSticky(event)) {
            pendingPause = PendingPause(event.packageName, event.className, event.timestamp)
            suppressedStop = null
        }
        return emptyList()
    }

    private fun handleStopped(event: Event, now: Long): List<ForegroundChange> {
        // Match both package and class. Old Activity STOPPED events can arrive after
        // in-app navigation has already resumed a new Activity in the same package.
        if (shouldSuppressStopped(event)) return emptyList()
        if (!matchesExactSticky(event)) return emptyList()
        stickyPackageName = null
        stickyClassName = null
        recordEvidence(event.timestamp, now)
        pendingPause = null
        return listOf(ForegroundChange(null, eventTime(event, now)))
    }

    private fun matchesExactSticky(event: Event): Boolean {
        return event.packageName == stickyPackageName && event.className == stickyClassName
    }

    private fun matchesPausedSticky(event: Event): Boolean {
        if (event.packageName != stickyPackageName) return false
        // UsageStats fallback only knows the package. In that state, accept the
        // next PAUSED for the package as the foreground Activity leaving.
        return stickyClassName == null || event.className == stickyClassName
    }

    private fun shouldSuppressStopped(event: Event): Boolean {
        val stop = suppressedStop ?: return false
        if (event.timestamp > stop.until) {
            suppressedStop = null
            return false
        }
        if (event.packageName != stop.pkg || event.className != stop.cls) return false
        suppressedStop = null
        return true
    }

    private fun recordEvidence(timestamp: Long, observedNow: Long) {
        val previous = lastForegroundChangeAt
        lastForegroundChangeAt = when {
            previous == null -> timestamp
            // 系统墙钟真的回拨时允许重新建立水位；普通迟到事件不能把水位拉低。
            observedNow < previous -> timestamp
            else -> maxOf(previous, timestamp)
        }
    }

    private fun eventTime(event: Event, now: Long): Long = event.timestamp.coerceAtLeast(now)
}
