package me.excuse.app.overlay

/** 与 IME padding 相同的临界阻尼手感：快速收敛、不过冲。 */
internal const val OVERLAY_MOTION_SPRING_STIFFNESS = 800f

/** 入场需要在用户察觉到等待前完成；快速起步，末端平缓收住。 */
internal const val OVERLAY_ENTER_DURATION_MS = 180

internal const val OVERLAY_EXIT_FADE_MS = 160L
