package me.excuse.app.overlay

/**
 * 悬浮窗诊断开关。**默认必须是 false，不要提交 true。**
 *
 * 打开后：
 * - `InterceptOverlay` 每帧把 IME inset、按钮让位进度、hide 状态打到 logcat tag `ReasonIme`，
 *   同时追加到应用私有目录的 `reason-ime-trace.log`。
 * - 悬浮窗**去掉 `FLAG_SECURE`**，使 `adb shell screenrecord` 能录到内容。
 *
 * 去掉 FLAG_SECURE 是为了逐帧看清「屏幕下方闪一下」到底闪的是什么：
 * Paper 底色说明是布局空档，下层 app 内容说明是窗口缓冲问题，黑色/导航栏说明是系统合成。
 * 这三种可能对应三种完全不同的修法，靠肉眼和猜区分不开。
 *
 * 只在 debug build 生效（每处都叠加了 `BuildConfig.DEBUG`），但仍然是隐私相关的旁路，
 * 诊断完必须改回 false。
 */
internal const val OVERLAY_DIAGNOSTICS_ENABLED = false
