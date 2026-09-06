package me.excuse.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import me.excuse.app.BuildConfig
import me.excuse.app.ui.theme.ExcuseTheme

/**
 * 预挂载式悬浮窗管理器。
 *
 * 设计：
 *  - service onCreate 调用 [prepare] —— 一次性 `wm.addView` 挂个常驻 FrameLayout + ComposeView，
 *    默认 `visibility = GONE` 且窗口 flags 含 `NOT_FOCUSABLE | NOT_TOUCHABLE` 让 touch 穿透
 *  - Compose 根和主题也只创建一次；[show] 只替换根内的内容状态，避免首次弹窗才初始化 composition
 *  - [warmUp] 可在服务空闲时用不可见、不可触摸的完整页面预热组合 / 测量 / 字体排版
 *  - [show] 不再走 addView，只是：① 替换预热内容；② updateViewLayout 切窗口 flags；
 *    ③ 让真实内容在 INVISIBLE 下完成一帧重组/测量，再于下一帧揭示
 *  - [dismiss] 反过来：GONE + 切回 untouchable + 清空内容状态
 *  - [release] 在 service onDestroy 调用，把 view 从 WindowManager 摘下
 *
 * 与最初实现相比省下的开销：每次 show 不用再走 `wm.addView` 系统调用 + ComposeView 构造
 * + LifecycleOwner 三件套 setup（合计 100-300ms）。setContent 本身的 recomposition 还在。
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var rootView: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var showing = false
    private var revealPending = false
    private var transitionGeneration = 0
    private val overlayContent = mutableStateOf<(@Composable () -> Unit)?>(null)

    /** service onCreate 调用 —— 预挂载常驻 overlay window。
     *  权限未授予 / 任何意外 → 返回 false；下次 show() 会再次尝试。
     *  整段套兜底 try-catch，宁可 prepare 失败也别让 service 闪退。 */
    @SuppressLint("InflateParams")
    fun prepare(): Boolean {
        rootView?.let { existing ->
            if (existing.isAttachedToWindow && composeView != null) return true
            if (existing.isAttachedToWindow) {
                if (!detachCurrentWindow("prepare-corrupt")) return false
            } else {
                clearWindowReferences(existing)
            }
        }
        return try {
            doPrepare()
        } catch (e: Exception) {
            Log.w(DIAG_TAG, "prepare failed", e)
            false
        }
    }

    private fun doPrepare(): Boolean {
        val owner = OverlayLifecycleOwner()
        val cv = ComposeView(context)

        val root = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true  // 显示时吃掉 touch，保护下面的 app
            addView(
                cv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        // 关键：tag 必须设到 root（FrameLayout）上 —— Compose 调 findViewTreeLifecycleOwner
        // 是从 window 的最顶层 View 开始向上找，root 才是顶层，cv 是它的 child。
        // 只设 cv 上 Compose 找不到，崩 "ViewTreeLifecycleOwner not found from FrameLayout"。
        owner.attachToView(root)
        // cv 也设一份，安全冗余 + 节省一次向上查找
        owner.attachToView(cv)
        owner.start()

        // composition 和主题跟 window 一起常驻。此前直到第一次 show() 才 setContent，
        // Compose runtime / 主题首次组合的成本会正好压在弹窗首帧上。
        cv.setContent {
            ExcuseTheme {
                overlayContent.value?.invoke()
            }
        }

        val params = makeParams(visibleAndFocusable = false)
        return try {
            wm.addView(root, params)
            rootView = root
            composeView = cv
            lifecycleOwner = owner
            true
        } catch (e: Exception) {
            try { owner.stop() } catch (_: Exception) {}
            Log.w(DIAG_TAG, "addView failed", e)
            false
        }
    }

    /**
     * 在窗口仍不可见、不可触摸时组合一份代表性的完整内容。
     *
     * 使用 INVISIBLE 而不是 GONE，让 ViewRoot 完成 measure/layout 和文字排版；真实 Surface
     * 首绘由 [show] 的 alpha=0 reveal 阶段支付。内容会保留到真正 show，提前支付 Compose、
     * 主题、输入框和字体管线的冷启动成本，又不常驻一张透明全屏缓冲。
     */
    fun warmUp(content: @Composable () -> Unit): Boolean {
        if (!prepare()) return false
        val root = rootView ?: return false
        // dismiss() 会先把 showing 置 false 再做 160ms 淡出；不要在这段窗口里抢掉退出动画。
        if (showing || root.visibility == View.VISIBLE) return false

        return try {
            transitionGeneration += 1
            root.animate().cancel()
            root.alpha = 1f
            updateFlags(visibleAndFocusable = false)
            setImmersive(false)
            overlayContent.value = content
            root.visibility = View.INVISIBLE
            root.requestLayout()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 只读暴露当前 window 是否已在显示，用于区分首次入场与原地换内容。 */
    fun isShowing(): Boolean =
        showing && rootView?.isAttachedToWindow == true && rootView?.visibility == View.VISIBLE

    /** Home 交接只让出键盘焦点，仍用原窗口吃掉卡片点击；绝不能和 dismiss 一起穿透触摸。 */
    fun releaseFocusForHome(): Boolean {
        if (!isShowing()) return true
        val root = rootView ?: return false
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return false
        val previousFlags = params.flags
        params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        return try {
            wm.updateViewLayout(root, params)
            true
        } catch (error: Exception) {
            params.flags = previousFlags
            Log.w(DIAG_TAG, "home focus handoff failed", error)
            false
        }
    }

    fun show(content: @Composable () -> Unit): Boolean =
        showInternal(content, mayRebuildWindow = true)

    private fun showInternal(
        content: @Composable () -> Unit,
        mayRebuildWindow: Boolean,
    ): Boolean {
        if (!prepare()) {
            return if (mayRebuildWindow) rebuildAndShow(content) else false
        }
        val root = rootView ?: return false
        if (!root.isAttachedToWindow || composeView == null) {
            return if (mayRebuildWindow) rebuildAndShow(content) else false
        }

        return try {
            val wasShowing = showing
            val wasRevealPending = revealPending
            transitionGeneration += 1
            root.animate().cancel()
            root.isClickable = true
            // 根 composition / 主题已经在 prepare() 创建，完整节点也可由 warmUp() 提前组合。
            // 这里仅替换真实参数和回调，不在用户看见弹窗时重新 setContent。
            overlayContent.value = content
            // 已经 showing 时不要再翻 visibility / flag —— Prompting→Prompting 切换 app 时
            // 复用同一 overlay 仅替换内容状态，避免 WindowManager updateViewLayout
            // 跟 Compose recomposition 抢窗造成上一个 pkg 的内容残留一帧。
            val focusReleasedForHome =
                (root.layoutParams as? WindowManager.LayoutParams)?.flags?.let {
                    it and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
                } == true
            if (!wasShowing || focusReleasedForHome) {
                if (!updateFlags(visibleAndFocusable = true)) {
                    return if (mayRebuildWindow) rebuildAndShow(content) else false
                }
                setImmersive(true)
            }
            // 若两帧预布局期间又收到一次 show（极快的 Prompting A→B），新的 generation
            // 会取消旧 reveal；这里必须为新内容重新排一次，否则窗口会停在 INVISIBLE。
            if (!wasShowing || wasRevealPending || root.visibility != View.VISIBLE) {
                // 不把真实参数写入后的首次重组、文本测量和输入框布局暴露在可见首帧。
                // 根 View 以 alpha=0 真正 VISIBLE 两帧，Surface 分配、首绘和文字准备都会发生，
                // 但用户看不到半成品；完成后只切根 alpha，不再在可见首帧创建缓冲。
                root.alpha = 0f
                root.visibility = View.VISIBLE
                root.requestLayout()
                composeView?.requestLayout()
                showing = true
                revealPending = true
                val revealGeneration = transitionGeneration
                root.postOnAnimation {
                    if (showing && revealGeneration == transitionGeneration) {
                        root.postOnAnimation {
                            if (showing && revealGeneration == transitionGeneration) {
                                root.alpha = 1f
                                revealPending = false
                            }
                        }
                    }
                }
            } else {
                root.alpha = 1f
            }
            showing = true
            true
        } catch (e: Exception) {
            Log.w(DIAG_TAG, "show failed; rebuilding", e)
            // view 被系统移除或 update 状态损坏时，旧 root 已不再可信。只重建一次，
            // 避免状态机停在 Prompting、实际却没有窗口，且不做高频重试。
            if (mayRebuildWindow) rebuildAndShow(content) else {
                dismiss()
                false
            }
        }
    }

    private fun rebuildAndShow(content: @Composable () -> Unit): Boolean {
        if (!detachCurrentWindow("rebuild")) return false
        if (!prepare()) return false
        return showInternal(content, mayRebuildWindow = false)
    }

    fun dismiss(immediately: Boolean = false) {
        val root = rootView ?: run {
            showing = false
            return
        }
        // 关屏不能等动画帧，直接清空内容和触摸遮挡。
        if (immediately) {
            showing = false
            revealPending = false
            transitionGeneration += 1
            completeDismiss(root)
            return
        }
        if (!showing || root.visibility != View.VISIBLE) {
            completeDismiss(root)
            return
        }

        if (revealPending) {
            showing = false
            revealPending = false
            transitionGeneration += 1
            completeDismiss(root)
            return
        }

        showing = false
        transitionGeneration += 1
        val generation = transitionGeneration
        try {
            // 先停止接收输入，避免淡出期间按钮连点；内容和不透明背景再一起短暂淡出，
            // 让确认后的目标 app 自然接上，而不是单帧 GONE。
            updateFlags(visibleAndFocusable = false)
            root.animate().cancel()
            root.animate()
                .alpha(0f)
                .setDuration(OVERLAY_EXIT_FADE_MS)
                .setInterpolator(PathInterpolator(0f, 0f, 0.2f, 1f))
                .withEndAction {
                    if (!showing && generation == transitionGeneration) {
                        completeDismiss(root)
                    }
                }
                .start()
        } catch (error: Exception) {
            Log.w(DIAG_TAG, "dismiss failed", error)
            completeDismiss(root)
        }
    }


    /** service onDestroy 调用 —— 把 view 摘下，终结 lifecycle */
    fun release() {
        detachCurrentWindow("release")
    }

    /**
     * 只有确认旧 root 已 detached 才丢引用。removeViewImmediate 抛错后若仍 attached，
     * 保留引用并把它降为 GONE/untouchable；否则重建新窗会留下不可回收的双 window。
     */
    private fun detachCurrentWindow(stage: String): Boolean {
        transitionGeneration += 1
        val root = rootView
        if (root == null) {
            clearWindowReferences(null)
            return true
        }

        try { root.animate().cancel() } catch (_: Exception) {}
        if (root.isAttachedToWindow) {
            try {
                wm.removeViewImmediate(root)
            } catch (error: Exception) {
                Log.w(DIAG_TAG, "$stage removeViewImmediate failed", error)
            }
        }
        if (root.isAttachedToWindow) {
            failSafeAttachedWindow(root)
            Log.w(DIAG_TAG, "$stage kept attached root; rebuild aborted")
            return false
        }

        clearWindowReferences(root)
        return true
    }

    private fun failSafeAttachedWindow(root: FrameLayout) {
        try { root.animate().cancel() } catch (_: Exception) {}
        root.visibility = View.GONE
        root.alpha = 1f
        root.isClickable = false
        updateFlags(visibleAndFocusable = false)
        overlayContent.value = null
        showing = false
        revealPending = false
    }

    private fun clearWindowReferences(expectedRoot: FrameLayout?) {
        if (expectedRoot != null && expectedRoot !== rootView) return
        try { lifecycleOwner?.stop() } catch (_: Exception) {}
        rootView = null
        composeView = null
        overlayContent.value = null
        lifecycleOwner = null
        showing = false
        revealPending = false
    }

    private fun completeDismiss(root: FrameLayout) {
        if (root !== rootView) return
        try {
            root.animate().cancel()
            root.visibility = View.GONE
            root.alpha = 1f
            setImmersive(false)
            updateFlags(visibleAndFocusable = false)
            // 根 composition 保持常驻，只清掉业务内容，既释放回调引用又避免下次重建主题。
            overlayContent.value = null
        } catch (error: Exception) {
            Log.w(DIAG_TAG, "completeDismiss failed", error)
        }
        showing = false
        revealPending = false
    }

    @Suppress("DEPRECATION")
    private fun makeParams(visibleAndFocusable: Boolean): WindowManager.LayoutParams {
        val baseFlags = (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                // TYPE_APPLICATION_OVERLAY 无法像 Activity Window 一样可靠指定透明状态栏；
                // Nubia 等 ROM 会把 TRANSLUCENT_STATUS 实现成带底色的独立系统栏。
                // 直接保持真正全屏，标题可绘制到屏幕顶端且不会被系统栏遮挡。
                or WindowManager.LayoutParams.FLAG_FULLSCREEN
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        // FLAG_SECURE 是常态；只有 debug + 诊断开关同时为真时才摘掉，让 screenrecord
        // 能录到悬浮窗内容。见 OverlayDebug.kt —— 那个开关默认 false 且不允许提交 true。
        val secureFlags = if (BuildConfig.DEBUG && OVERLAY_DIAGNOSTICS_ENABLED) {
            baseFlags
        } else {
            baseFlags or WindowManager.LayoutParams.FLAG_SECURE
        }
        val flags = if (visibleAndFocusable) {
            secureFlags
        } else {
            secureFlags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    @Suppress("DEPRECATION")
    private fun setImmersive(enabled: Boolean) {
        rootView?.systemUiVisibility = if (enabled) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun updateFlags(visibleAndFocusable: Boolean): Boolean {
        val v = rootView ?: return false
        val current = (v.layoutParams as? WindowManager.LayoutParams) ?: return false
        val fresh = makeParams(visibleAndFocusable)
        current.flags = fresh.flags
        current.softInputMode = fresh.softInputMode
        current.layoutInDisplayCutoutMode = fresh.layoutInDisplayCutoutMode
        return try {
            wm.updateViewLayout(v, current)
            true
        } catch (error: Exception) {
            Log.w(DIAG_TAG, "updateViewLayout failed", error)
            false
        }
    }

    private companion object {
        const val DIAG_TAG = "ReasonOverlay"
    }
}
