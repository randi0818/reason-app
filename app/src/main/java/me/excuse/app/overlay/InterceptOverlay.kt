package me.excuse.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.excuse.app.BuildConfig
import me.excuse.app.ui.theme.Disabled
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Line
import me.excuse.app.ui.theme.Faint
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.MutedSoft
import me.excuse.app.ui.theme.Paper
import me.excuse.app.ui.theme.SurfaceBg
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InterceptOverlay(
    promptKey: String,
    animateEntry: Boolean,
    appName: String,
    reasonUseCountsToday: Map<String, Int>,
    submitting: Boolean = false,
    returningHome: Boolean = false,
    onConfirm: (reason: String, minutes: Int) -> Unit,
    onCancel: () -> Unit,
    onInteraction: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // 入场动画留在 package key 之外。预热内容立即排版；首次真实展示
    // 从 0→1；已经在屏上的 A→B 则直接保持 1，不会因子树换 key 露出一帧纯背景。
    val contentVisibility = remember(animateEntry) {
        MutableTransitionState(!animateEntry).apply { targetState = true }
    }
    val contentTransition = rememberTransition(
        transitionState = contentVisibility,
        label = "intercept-content-entry",
    )
    val contentProgress by contentTransition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = OVERLAY_ENTER_DURATION_MS,
                easing = LinearOutSlowInEasing,
            )
        },
        label = "intercept-content-progress",
    ) { visible ->
        if (visible) 1f else 0f
    }

    // Prompting A→B 不能通过 dismiss 重建 window，否则会把旧内容首帧和
    // WindowManager flag 翻转的 race 带回来。只以 package 为 key 重建业务子树：
    // window / 根 composition 仍复用，reason、时长、滚动、焦点和 IME 协程全部换新。
    LaunchedEffect(promptKey) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    // Surface 不随 package key 重建，任何时候都有一层不透明背景挡住下层 app。
    Surface(
        color = Paper,
        modifier = Modifier.fillMaxSize()
    ) {
        key(promptKey) {
            InterceptOverlayContent(
                appName = appName,
                reasonUseCountsToday = reasonUseCountsToday,
                submitting = submitting,
                returningHome = returningHome,
                contentProgress = contentProgress,
                onConfirm = onConfirm,
                onCancel = onCancel,
                onInteraction = onInteraction,
            )
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalLayoutApi::class)
// 大量 `if (BuildConfig.DEBUG && IME_TRACE_ENABLED)` 在默认配置下是 dead code（IME_TRACE_ENABLED=false）。
// 需要调试时一行改 true 全部激活，所以 lint warning 在这里是预期的，整体抑制。
@Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
@Composable
private fun InterceptOverlayContent(
    appName: String,
    reasonUseCountsToday: Map<String, Int>,
    submitting: Boolean,
    returningHome: Boolean,
    contentProgress: Float,
    onConfirm: (reason: String, minutes: Int) -> Unit,
    onCancel: () -> Unit,
    onInteraction: () -> Unit,
) {
    val presets = listOf(5, 10, 15, 30)
    var selectedPreset by remember { mutableStateOf<Int?>(null) }
    var customMinutes by remember { mutableIntStateOf(DEFAULT_CUSTOM_MINUTES) }
    var reasonFocused by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }

    val isCustomMode = selectedPreset == null
    val finalMinutes = selectedPreset ?: customMinutes

    val nextUseNumber = nextReasonUseNumber(reason, reasonUseCountsToday)
    val isDuplicate = nextUseNumber != null
    val duplicateMessage = nextUseNumber?.let { "今天第 $it 次用这个理由" }.orEmpty()
    val busy = submitting || returningHome
    val canConfirm = canConfirmReason(busy, reason, finalMinutes)
    val contentScrollState = rememberScrollState()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    // inset 对象本身不是 state 读取，getBottom() 才是。拿住它，让需要逐帧值的地方
    // 在协程里用 snapshotFlow 观察，不把每帧变化拖进 composition。
    val imeInsets = WindowInsets.ime
    // 键盘动画期间 imeBottom 每帧都变。在 composition 里读它 = 整棵 overlay 每帧重组；
    // 布局避让本来就由 layout 阶段的 imePadding() 完成，composition 并不需要这个值。
    // 只有 trace 打开时才付这份代价。
    val imeBottomPx = if (BuildConfig.DEBUG && IME_TRACE_ENABLED) imeInsets.getBottom(density) else 0
    // 某些手势导航 ROM 在 IME 关闭后仍会短暂保留一个非零 bottom（通常等于导航区高度）。
    // 布局和按钮显隐只认 isImeVisible；否则这段残留 inset 会把底部按钮永久挡在输入态。
    val imePresent = imeVisible
    val anyTextFieldFocused = reasonFocused
    val coroutineScope = rememberCoroutineScope()
    var scrollViewportBounds by remember { mutableStateOf<Rect?>(null) }
    var reasonFieldBounds by remember { mutableStateOf<Rect?>(null) }

    // —— IME 避让 ——
    // padding 只采用系统正在动画的 inset。不能在 focus 落下时先塞入上一轮键盘高度：
    // 那会让文字先单帧瞬移到终点，随后才接上系统动画。系统 inset 本身每帧变化，
    // 这里既不预占，也不再叠加第二层动画。
    val context = LocalContext.current
    // IME 从可见变为不可见但 focus 还在，分两种情况：
    //   a) 键盘曾为当前焦点升起过 → 用户按系统返回键关掉了它。立即释放输入态，让底部按钮
    //      和最终 IME 帧一起回来；焦点稍后清除，避免 ROM 的瞬时不可见误判。
    //   b) 键盘从未为当前焦点升起（快速切换输入框时，旧键盘正在关闭、等待新键盘重开）→
    //      保留输入态和焦点，给 IMM 足够时间重开；键盘一旦重新可见，本 effect 自动取消等待。
    val focusedFieldId = if (reasonFocused) 1 else 0
    val imeSeenSinceFocus = remember { booleanArrayOf(false) }
    val trackedFocusedFieldId = remember { intArrayOf(0) }
    val prevImeVisible = remember { booleanArrayOf(false) }
    var imeDismissedWhileFocused by remember { mutableStateOf(false) }
    // Overlay 会先用占位回调 warm-up；pointerInput 协程本身会跨重组存活，因此必须从
    // updated state 取当前 prompt 的回调，不能意外一直调用预热阶段的空 lambda。
    val currentOnInteraction = rememberUpdatedState(onInteraction)
    // 每次确认轻点输入框或真正获得焦点，都生成一枚新的 IME 打开请求。它既是显式的用户意图，也用来使上一轮
    // “键盘关闭后延迟清焦点”的协程失效。不能只等 isImeVisible 变 true 再取消：部分 ROM
    // 上报键盘可见会慢于实际动画起点，旧协程会在这段窗口里把刚出现的键盘关回去。
    var requestedImeFieldId by remember { mutableIntStateOf(0) }
    var imeOpenRequestGeneration by remember { mutableIntStateOf(0) }
    var imeOpenIntentActive by remember { mutableStateOf(false) }
    val currentReasonFocused = rememberUpdatedState(reasonFocused)
    val currentImeVisible = rememberUpdatedState(imeVisible)
    val currentImeOpenRequestGeneration = rememberUpdatedState(imeOpenRequestGeneration)

    fun registerTextInputIntent(fieldId: Int) {
        requestedImeFieldId = fieldId
        imeOpenRequestGeneration += 1
        // tap / focus 确认后立即进入输入态，不等 IME inset 的下一次回报。
        // 滑动手势不会走到这里，因此页面下滑不会误触发 show()。
        imeOpenIntentActive = true
        imeDismissedWhileFocused = false
        // 单纯点进输入框也属于交互。否则用户在初始 10s 超时临界点点开键盘时，
        // prompt 可能恰好被定时器 dismiss，看起来就像键盘“弹出后马上收起”。
        currentOnInteraction.value()
    }

    fun Modifier.stableImeIntent(fieldId: Int): Modifier = pointerInput(fieldId) {
        awaitEachGesture {
            // 先旁观 TextField 的 down，不消费它；只有整次手势最终被识别为 tap 才登记
            // IME 打开意图。父级 verticalScroll 一旦接管拖动，up 会以 cancellation 返回，
            // 从而避免在输入框上开始下滑时仍补发 keyboardController.show()。
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
            if (up != null) {
                registerTextInputIntent(fieldId)
            }
        }
    }

    // TextField 通常会自行唤起键盘；这里在焦点节点完成切换后再补一个幂等 show()，
    // 专门覆盖“同一个框仍有焦点、但上一轮 IME 正在收起”的快速重开路径。
    LaunchedEffect(imeOpenRequestGeneration, focusedFieldId, requestedImeFieldId) {
        if (
            imeOpenRequestGeneration > 0 &&
            focusedFieldId != 0 &&
            focusedFieldId == requestedImeFieldId
        ) {
            delay(IME_FOCUS_SETTLE_BEFORE_SHOW_MS)
            keyboardController?.show()
        }
    }

    LaunchedEffect(imeVisible) {
        if (imeVisible) imeOpenIntentActive = false
    }

    LaunchedEffect(imeOpenRequestGeneration) {
        if (imeOpenRequestGeneration > 0) {
            val requestGeneration = imeOpenRequestGeneration
            delay(IME_OPEN_INTENT_TIMEOUT_MS)
            val requestStillCurrent =
                currentImeOpenRequestGeneration.value == requestGeneration
            if (requestStillCurrent) {
                imeOpenIntentActive = false
            }
            if (
                shouldReleaseInputAfterImeOpenTimeout(
                    requestStillCurrent = requestStillCurrent,
                    textFieldFocused = currentReasonFocused.value,
                    imeSeenSinceFocus = imeSeenSinceFocus[0],
                    imeVisible = currentImeVisible.value,
                )
            ) {
                // 硬件键盘、关闭软键盘或 ROM 拒绝 show() 时，focus 会留在
                // TextField，但屏幕上没有需要让位的 IME。明确释放视觉输入态，
                // 让原有 hide/semantics/click 三者一起恢复，不只是把按钮画出来。
                imeDismissedWhileFocused = true
            }
        }
    }

    // key 里绝不能出现逐帧变化的 imeBottomPx：键盘下落期间它每帧都变，effect 会被每帧重启，
    // 下面的 grace delay 永远走不完，clearFocus 实际上从不执行。
    // 关闭沿只看 isImeVisible 的下降沿 —— 原来的“inset 归零”兜底其实被它完全覆盖
    // （prevImeBottom 只在 imeVisible 时才可能非零，蕴含 prevImeVisible=true）。
    LaunchedEffect(focusedFieldId, imeVisible, imeOpenRequestGeneration) {
        if (focusedFieldId != trackedFocusedFieldId[0]) {
            trackedFocusedFieldId[0] = focusedFieldId
            imeSeenSinceFocus[0] = false
            imeDismissedWhileFocused = false
        }

        val wasVisible = prevImeVisible[0]
        if (focusedFieldId == 0) {
            prevImeVisible[0] = imeVisible
            imeDismissedWhileFocused = false
            return@LaunchedEffect
        }

        if (imeVisible) {
            imeSeenSinceFocus[0] = true
            imeDismissedWhileFocused = false
        }
        prevImeVisible[0] = imeVisible

        val imeJustClosed = wasVisible && !imeVisible
        if (imeJustClosed) {
            val closingFieldId = focusedFieldId
            val requestGenerationAtClose = imeOpenRequestGeneration
            val graceMs = if (imeSeenSinceFocus[0]) {
                // 视觉输入态现在就释放；这段 grace 只保护焦点，不再拖住按钮。
                imeDismissedWhileFocused = true
                IME_CLOSE_FOCUS_CLEAR_GRACE_MS
            } else {
                IME_PENDING_REOPEN_GRACE_MS
            }
            delay(graceMs)
            if (
                !imeVisible &&
                focusedFieldId == closingFieldId &&
                imeOpenRequestGeneration == requestGenerationAtClose
            ) {
                focusManager.clearFocus(force = true)
            }
        }
    }

    val inputInteractionActive = anyTextFieldFocused && !imeDismissedWhileFocused

    // 隐藏必须即时，恢复则需要一个很短的“IME 确实保持关闭”窗口。否则快速 Back→重开时，
    // isImeVisible 在两次动作之间短暂为 false，按钮会多做一轮无意义的收放。
    // 这里只 debounce 恢复，不给打开路径增加任何等待；按钮一旦出现仍是完整可点状态。
    val bottomActionsShouldHide = imeOpenIntentActive || inputInteractionActive || imePresent
    var bottomActionsReleased by remember { mutableStateOf(true) }
    LaunchedEffect(bottomActionsShouldHide) {
        if (bottomActionsShouldHide) {
            bottomActionsReleased = false
        } else {
            delay(BOTTOM_ACTIONS_RELEASE_DEBOUNCE_MS)
            bottomActionsReleased = true
        }
    }
    val hideBottomActions = bottomActionsShouldHide || !bottomActionsReleased

    suspend fun keepFieldInsideViewport(fieldBounds: Rect?) {
        val viewport = scrollViewportBounds ?: return
        val field = fieldBounds ?: return
        val topGapPx = with(density) { 8.dp.toPx() }
        val bottomGapPx = with(density) { 16.dp.toPx() }
        val deltaPx = when {
            field.bottom > viewport.bottom - bottomGapPx -> field.bottom - (viewport.bottom - bottomGapPx)
            field.top < viewport.top + topGapPx -> field.top - (viewport.top + topGapPx)
            else -> 0f
        }
        if (abs(deltaPx) >= 1f) {
            val target = (contentScrollState.value + deltaPx.roundToInt())
                .coerceIn(0, contentScrollState.maxValue)
            contentScrollState.scrollTo(target)
        }
    }

    // 不再把每一帧 imeBottom 当 LaunchedEffect key（旧做法会不断取消/重启协程）。
    // imePadding 在 layout 阶段同帧更新 maxValue；单个持续 collector 只负责保持焦点框可见。
    LaunchedEffect(reasonFocused) {
        if (reasonFocused) {
            snapshotFlow { contentScrollState.maxValue }.collect {
                keepFieldInsideViewport(reasonFieldBounds)
            }
        }
    }

    fun finishTextInput() {
        // 只请求关闭 IME，不在动画起点清焦。这样 Done 和手势返回走同一路径：
        // 关闭边沿再由上面的 effect 延迟 120ms clearFocus。之前的问题来自
        // hide()+clearFocus 同时下发的双指令，这里仍然只发一个指令。
        if (BuildConfig.DEBUG && IME_TRACE_ENABLED) {
            val mark = "t=${SystemClock.uptimeMillis()} FINISH_TEXT_INPUT_CALLED " +
                "imeBottom=$imeBottomPx"
            Log.d("ReasonIme", mark)
            coroutineScope.launch { appendImeTrace(context, mark) }
        }
        if (keyboardController != null) {
            keyboardController.hide()
        } else {
            // 极少数无 SoftwareKeyboardController 的宿主兜底；正常 overlay 不走这里。
            focusManager.clearFocus(force = true)
        }
    }
    if (BuildConfig.DEBUG && IME_TRACE_ENABLED) {
        val prev = remember { intArrayOf(0) }  // [prevImeBottomPx]
        val prevHide = remember { booleanArrayOf(false) }  // [prevHideBottomActions]
        LaunchedEffect(
            reasonFocused,
            imeVisible,
            imePresent,
            imeBottomPx,
            hideBottomActions,
        ) {
            val imeDelta = imeBottomPx - prev[0]
            val imeJump = kotlin.math.abs(imeDelta) > 120  // IME inset 单帧大跳变（潜在 flash 源）
            val hideFlip = hideBottomActions != prevHide[0]
            prev[0] = imeBottomPx
            prevHide[0] = hideBottomActions
            // progress 用和 bottomActionsProgress() 完全相同的公式，只是在这里为了记录而
            // 在 composition 里算一遍。要看的是它和 imeBottom 是否真的同步：
            //   - imeBottom 逐帧小步涨、progress 跟着逐帧降 → 让位确实跟住了键盘，
            //     那么闪的来源不在这个布局里，要往窗口缓冲 / 系统合成方向查。
            //   - imeBottom 带 IME_JUMP 单帧从 0 跳到满 → 覆盖量驱动等于没有中间帧，
            //     按钮仍然是一帧消失，这个修法对本机无效，得改用别的对齐手段。
            val traceFullPx = with(density) {
                (BOTTOM_ACTION_HEIGHT + 24.dp).toPx()
            }
            val traceProgress = if (!hideBottomActions) {
                1f
            } else {
                1f - (imeBottomPx / traceFullPx).coerceIn(0f, 1f)
            }
            val line =
                "t=${SystemClock.uptimeMillis()} " +
                    "reasonF=$reasonFocused " +
                    "imeVisible=$imeVisible imePresent=$imePresent " +
                    "imeBottom=$imeBottomPx delta=$imeDelta " +
                    (if (imeJump) "IME_JUMP " else "") +
                    "progress=${"%.3f".format(traceProgress)} " +
                    "seen=${imeSeenSinceFocus[0]} " +
                    "hide=$hideBottomActions" +
                    (if (hideFlip) " HIDE_FLIP" else "")
            Log.d("ReasonIme", line)
            appendImeTrace(context, line)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val compactHeight = maxHeight < 720.dp
        val tallHeight = maxHeight > 840.dp
        val horizontalPadding = when {
            maxWidth < 360.dp -> 22.dp
            maxWidth > 430.dp -> 32.dp
            else -> 28.dp
        }
        val verticalPadding = if (compactHeight) 24.dp else 32.dp
        val contentTopSpacer = when {
            landscape -> 0.dp
            compactHeight -> 44.dp
            tallHeight -> 76.dp
            else -> 60.dp
        }
        val sectionGap = if (compactHeight) 12.dp else 16.dp
        val reasonHeight = if (compactHeight) 100.dp else 110.dp
        val durationScrollState = rememberScrollState()

        val durationPane = rememberOverlayPane {
            Column(verticalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else sectionGap)) {
                if (!landscape) Spacer(Modifier.height(4.dp))

                Text(
                    text = "想用多久",
                    fontSize = 13.sp,
                    color = Muted,
                    letterSpacing = 0.5.sp
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(if (landscape) 6.dp else 10.dp)
                ) {
                    presets.forEach { m ->
                        DurationChip(
                            label = "$m 分钟",
                            selected = selectedPreset == m,
                            enabled = !busy,
                            onClick = {
                                focusManager.clearFocus(force = true)
                                selectedPreset = m
                                onInteraction()
                            }
                        )
                    }
                    DurationChip(
                        label = "自定义",
                        selected = isCustomMode,
                        enabled = !busy,
                        onClick = {
                            // Slider 不需要输入法；从理由框切过来时主动结束文本输入，
                            // 避免用户已经选择滑杆、屏幕上却还留着一块无关的键盘。
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            selectedPreset = null
                            onInteraction()
                        }
                    )
                }

                // 键盘起来时也把时间条淡出。两个理由：
                //
                // 1) 语义上本来就该如此。选「自定义」已经会主动 clearFocus + hide()，
                //    也就是说滑杆和键盘早就被定义为互斥；这里只是把这条规则补成对称的，
                //    不是为了绕开 bug 才加的特例。写理由的时候不会同时在调时长。
                //
                // 2) 它同时消掉了闪动的最后一个来源。时间条是滚动区最底部的内容，而滚动区
                //    挂着 imePadding()：inset 比键盘的像素早一两帧到，viewport 先缩、
                //    深色滑杆先被裁掉，键盘还没盖上 —— 露出的 Paper 底色就是实测到的那一闪。
                //    底部按钮那边可以靠「盖满才收」躲开，滚动区不行，imePadding 必须跟着
                //    inset 走。所以这里换个思路：让那块区域在 viewport 开始缩之前就已经
                //    是空背景，背景再被键盘盖住没有任何视觉落差，也就无从闪起。
                //
                // 淡出走原来那条 spring，是一次看得见的、有意的过渡，而不是两帧的空洞。
                val customRowAlpha by animateFloatAsState(
                    targetValue = if (isCustomMode && !hideBottomActions) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = OVERLAY_MOTION_SPRING_STIFFNESS,
                    ),
                    label = "intercept-custom-row-alpha",
                )
                // 可见性和可交互性必须同一个条件：键盘期间滑杆是透明的，若还留着手势，
                // 就成了一根看不见却照样能拖的滑杆，等于凭空改掉用户的时长。
                // 底部按钮那边已经踩过同一个坑（hideBottomActions 期间挡掉 onClick）。
                val customRowInteractive = isCustomMode && !hideBottomActions && !busy
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = CUSTOM_DURATION_CONTROL_HEIGHT)
                        // 始终保留占位，切换模式不重排；放大字体时允许刻度撑高，避免底部裁字。
                        .graphicsLayer { alpha = customRowAlpha }
                        .then(
                            if (customRowInteractive) Modifier
                            else Modifier.clearAndSetSemantics {}
                        )
                ) {
                    DurationTimeBarSlider(
                        value = customMinutes,
                        enabled = customRowInteractive,
                        onValueChange = { minutes ->
                            customMinutes = minutes
                            onInteraction()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        val reasonPane = rememberOverlayPane {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .onGloballyPositioned { scrollViewportBounds = it.boundsInRoot() }
                    // 保留原有焦点锚定；横屏时只滚理由栏，不带动右侧的决定按钮。
                    .verticalScroll(contentScrollState, enabled = !reasonFocused),
                verticalArrangement = Arrangement.spacedBy(sectionGap),
            ) {
                Spacer(Modifier.height(contentTopSpacer))

                // 拦截窗标题：把 app 名和"一个理由"拆开，长 app 名不会挤断句尾。
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "给",
                        fontSize = if (landscape) 13.sp else 17.sp,
                        color = Muted,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = appName,
                        fontSize = if (landscape) 28.sp else 42.sp,
                        fontWeight = FontWeight.Light,
                        color = Ink,
                        lineHeight = if (landscape) 34.sp else 46.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "一个理由",
                        fontSize = if (landscape) 20.sp else 26.sp,
                        fontWeight = FontWeight.Light,
                        color = Ink,
                        lineHeight = if (landscape) 26.sp else 32.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                // TextField + 可变高错误区包成外层的一个 child，避免错误区高度为 0 时
                // Arrangement.spacedBy 仍多算一道 gap，改变正常态布局。
                Column {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { input ->
                            val lineBreakIndex = input.indexOfAny(charArrayOf('\n', '\r'))
                            when {
                                lineBreakIndex < 0 -> reason = input
                                // 换行只出现在末尾 → 打字回车，视同 Done
                                input.drop(lineBreakIndex).all { it == '\n' || it == '\r' } -> {
                                    reason = input.take(lineBreakIndex)
                                    finishTextInput()
                                }
                                // 换行夹在中间 → 粘贴的多行文本，折成空格保留全文，不关键盘
                                else -> reason = input.replace(LINE_BREAK_RUN, " ")
                            }
                            onInteraction()
                        },
                        readOnly = busy,
                        placeholder = { Text("查一条消息…", color = Faint, fontSize = 14.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(reasonHeight)
                            .stableImeIntent(fieldId = 1)
                            .onGloballyPositioned { reasonFieldBounds = it.boundsInRoot() }
                            .onFocusChanged { focusState ->
                                val nowFocused = focusState.isFocused
                                if (nowFocused && !reasonFocused) {
                                    registerTextInputIntent(fieldId = 1)
                                }
                                reasonFocused = nowFocused
                            },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { finishTextInput() }
                        ),
                        isError = isDuplicate,
                        shape = RoundedCornerShape(0.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = Ink),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = SurfaceBg,
                            unfocusedContainerColor = SurfaceBg,
                            focusedIndicatorColor = Ink,
                            unfocusedIndicatorColor = Line
                        )
                    )

                    val duplicateMessageHeight by animateDpAsState(
                        targetValue = if (isDuplicate) sectionGap + 20.dp else 0.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = OVERLAY_MOTION_SPRING_STIFFNESS,
                        ),
                        label = "intercept-duplicate-height",
                    )
                    val duplicateMessageAlpha by animateFloatAsState(
                        targetValue = if (isDuplicate) 1f else 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = OVERLAY_MOTION_SPRING_STIFFNESS,
                        ),
                        label = "intercept-duplicate-alpha",
                    )
                    Box(
                        modifier = Modifier
                            .height(duplicateMessageHeight)
                            .clipToBounds()
                            .graphicsLayer { alpha = duplicateMessageAlpha }
                            .then(if (isDuplicate) Modifier else Modifier.clearAndSetSemantics {})
                    ) {
                        Text(
                            text = duplicateMessage,
                            color = Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = sectionGap),
                        )
                    }
                }
                if (!landscape) durationPane()
            }
        }
        val actionsPane = rememberOverlayPane {
            // 底部按钮不再整块挂载 / 卸载。硬开关是单帧的布局跳变：反复开合键盘时
            // 按钮会在 debounce 边界上一闪即逝，同时把滚动区高度来回改一次，底部
            // 文字跟着抖。改成高度 + alpha 一起收放后，中途反向也会被平滑接住。
            // sectionGap 这道间距也算进来：外层 Column 已经不用 spacedBy，
            // 收起时这一整块必须能真正收到 0，滚动区才拿得回全部高度。
            val actionRowsHeight = if (landscape) BOTTOM_ACTION_HEIGHT * 2 + 8.dp else BOTTOM_ACTION_HEIGHT
            val bottomActionsHeight = sectionGap + actionRowsHeight + verticalPadding
            // 让位进度不再跑自己的 spring，而是直接由键盘的实际覆盖量决定。
            //
            // 旧版本里 hideBottomActions 在「点一下输入框」的瞬间就翻 true
            // （registerTextInputIntent 明确不等 inset 回报），按钮随即按自己的
            // 曲线收起；而 IME 要晚若干帧才升上来。中间那几帧按钮已经没了、
            // 键盘还没到，露出的一条 Surface 底色就是实机上「屏幕下方闪一下」。
            // 两个独立时钟赛跑，调 stiffness 只能改变缝的宽度，消不掉缝。
            //
            // 现在键盘盖住这块区域的百分之多少，按钮就让开百分之多少，两者共用
            // 系统同一条动画曲线 —— 空档由构造消失，而不是靠调参凑。
            // 系统压根没升起键盘时 coverage 恒为 0，按钮保持完整可见，
            // 也就顺带去掉了「按钮被永久隐藏」这一类兜底的必要性。
            // 按钮**要么整块在，要么整块没**，而且切换只允许发生在键盘已经完全盖住
            // 这条带子之后 —— 也就是说，那一次布局变化永远发生在用户看不见的地方。
            //
            // 上一版按覆盖比例连续收起，仍然会闪，原因是 inset 和像素不同步：
            // 系统先把新的 imeBottom 报给我们，键盘自己的 surface 要再过一两帧才画上去。
            // 只要让位是"跟着 inset 走"，就永远比键盘的像素早那一两帧，那一两帧里
            // 按钮已经缩走、键盘还没盖上，露出的就是 Paper 底色 —— 实测到的正是纯背景色。
            //
            // 所以这里不再试图和键盘对齐（对不齐，我们拿不到它的实际上屏时刻），
            // 改成永远偏向"按钮还在"：imeBottom 没盖满整条带子之前保持整块渲染，
            // 露在键盘上方的部分始终是真的按钮而不是空背景；盖满之后才收到 0，
            // 此时整块都在键盘底下，收起过程不可见。开合两个方向同一条规则：
            // 关键盘时按钮会在键盘退走之前就回到位，同样是先被盖住、后被让出来。
            fun bottomActionsProgress(density: Density): Float {
                if (!hideBottomActions) return 1f
                val fullPx = with(density) { bottomActionsHeight.toPx() }
                if (fullPx <= 0f) return 0f
                return if (imeInsets.getBottom(density) >= fullPx) 0f else 1f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = bottomActionsProgress(this)
                        clip = true
                    }
                    // 进度只在 layout / draw 阶段读 —— GraphicsLayerScope 和
                    // MeasureScope 都是 Density，可以直接拿去算 inset。读 IME inset
                    // 本身是快照读，落在这两个阶段只会触发重布局 / 重绘，整棵 overlay
                    // 一帧都不用重组，和原来那条「不把逐帧 inset 带进 composition」
                    // 的原则一致。按钮始终按整高测量，节点只对外报当前高度，被上面的
                    // clip 从下沿裁掉 —— 收起方向和键盘升起一致。
                    .layout { measurable, constraints ->
                        val fullPx = bottomActionsHeight.roundToPx()
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = fullPx, maxHeight = fullPx)
                        )
                        val shownPx = (fullPx * bottomActionsProgress(this))
                            .roundToInt()
                            .coerceIn(0, fullPx)
                        layout(placeable.width, shownPx) { placeable.place(0, 0) }
                    }
                    .then(
                        if (hideBottomActions) Modifier.clearAndSetSemantics {}
                        else Modifier
                    )
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bottomActionsHeight)
                        .padding(top = sectionGap, bottom = verticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = if (landscape) 1 else 2,
                ) {
                    OutlinedButton(
                        onClick = {
                            if (BuildConfig.DEBUG && IME_TRACE_ENABLED) {
                                val mark = "t=${SystemClock.uptimeMillis()} CANCEL_PRESSED " +
                                    "imeBottom=$imeBottomPx " +
                                    "hide=$hideBottomActions"
                                Log.d("ReasonIme", mark)
                                coroutineScope.launch { appendImeTrace(context, mark) }
                            }
                            // 收起动画期间按钮还在树上且短暂半透明。它此刻正被
                            // 升起的键盘盖住，但别让边缘的一次误触真的取消掉。
                            if (!hideBottomActions) onCancel()
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).height(BOTTOM_ACTION_HEIGHT),
                        shape = RoundedCornerShape(0.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (busy) Disabled else Ink),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SurfaceBg,
                            contentColor = Ink,
                            disabledContainerColor = SurfaceBg,
                            disabledContentColor = Muted,
                        )
                    ) {
                        Text(if (returningHome) "正在退出…" else "我不用了", fontSize = 15.sp)
                    }
                    Button(
                        onClick = {
                            if (canConfirm && !hideBottomActions) {
                                if (BuildConfig.DEBUG && IME_TRACE_ENABLED) {
                                    val mark = "t=${SystemClock.uptimeMillis()} CONFIRM_PRESSED " +
                                        "imeBottom=$imeBottomPx " +
                                        "hide=$hideBottomActions canConfirm=$canConfirm"
                                    Log.d("ReasonIme", mark)
                                    coroutineScope.launch { appendImeTrace(context, mark) }
                                }
                                onConfirm(reason.trim(), finalMinutes)
                            }
                        },
                        enabled = canConfirm,
                        modifier = Modifier.weight(1f).height(BOTTOM_ACTION_HEIGHT),
                        shape = RoundedCornerShape(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Ink,
                            contentColor = Paper,
                            disabledContainerColor = Disabled,
                            disabledContentColor = Paper
                        )
                    ) {
                        Text(
                            if (submitting) "正在确认…" else "确认",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val pageModifier = Modifier
                .widthIn(max = if (landscape) 1100.dp else 480.dp)
                .fillMaxSize()
                .then(if (landscape) Modifier.windowInsetsPadding(
                    WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility)
                        .only(WindowInsetsSides.Horizontal)
                ) else Modifier)
                .padding(horizontal = horizontalPadding)
                .padding(top = verticalPadding)
                .graphicsLayer {
                    alpha = contentProgress
                    translationY = (1f - contentProgress) * 8.dp.toPx()
                }
            if (landscape) {
                Row(pageModifier, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(bottom = verticalPadding)) {
                        reasonPane()
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).imePadding().verticalScroll(durationScrollState)) {
                            durationPane()
                        }
                        actionsPane()
                    }
                }
            } else {
                Column(pageModifier) {
                    Box(Modifier.weight(1f)) { reasonPane() }
                    actionsPane()
                }
            }
        }
    }
}

// 横竖屏只是把同一份节点搬到另一栏，避免重建输入框导致选区、焦点和 IME 请求丢失。
@Composable
private fun rememberOverlayPane(content: @Composable () -> Unit): @Composable () -> Unit {
    val currentContent = rememberUpdatedState(content)
    return remember { movableContentOf { currentContent.value() } }
}

@Composable
private fun DurationChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val selectedProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = OVERLAY_MOTION_SPRING_STIFFNESS,
        ),
        label = "duration-chip-selection",
    )
    val containerColor = lerp(SurfaceBg, Ink, selectedProgress)
    val contentColor = lerp(Ink, Paper, selectedProgress)
    val borderColor = lerp(MutedSoft, Ink, selectedProgress)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        shape = RoundedCornerShape(0.dp),  // Metro 风：方方正正
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            borderColor
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 18.dp, vertical = 10.dp
        )
    ) {
        Text(label, fontSize = 14.sp)
    }
}

/**
 * 自定义时长的横向 time bar。
 *
 * 视觉沿用数据页 Timeline24h：灰色底条、黑色已选区间、条下方的实心三角游标。
 * 真正的手势与无障碍行为交给透明的 Material Slider，避免手写拖动与父级滚动争抢。
 */
@Composable
private fun DurationTimeBarSlider(
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var lastHapticMinute by remember { mutableIntStateOf(value) }
    LaunchedEffect(value) {
        lastHapticMinute = value
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "拖动调整",
                color = Muted,
                fontSize = 13.sp,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value.toString(),
                    color = Ink,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 30.sp,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "分钟",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DURATION_SLIDER_TOUCH_HEIGHT),
        ) {
            val trackColor = Line
            val activeColor = Ink
            Canvas(Modifier.fillMaxSize()) {
                // 可见轨道与上方理由输入框共用完整内容宽度，不再为 Material Slider
                // 的隐形 thumb 预留左右 inset；视觉边线严格对齐。
                val trackStart = 0f
                val trackWidth = size.width
                val trackTop = 3.dp.toPx()
                val trackHeight = 26.dp.toPx()
                val fraction =
                    (value - MIN_CUSTOM_MINUTES).toFloat() /
                        (MAX_CUSTOM_MINUTES - MIN_CUSTOM_MINUTES).toFloat()
                val clampedFraction = fraction.coerceIn(0f, 1f)
                val atEndpoint = value == MIN_CUSTOM_MINUTES || value == MAX_CUSTOM_MINUTES
                // 中间刻度保持原先正好的 12×8dp；只有严格裁半后视觉面积不足的两个端点
                // 使用更大的母三角尺寸，因此不会把整条滑杆的游标都一起放大。
                val halfBase = (if (atEndpoint) 9.dp else 6.dp).toPx()
                val cursorHeight = (if (atEndpoint) 11.dp else 8.dp).toPx()
                val progressWidth = trackWidth * clampedFraction
                // 尖端必须与黑条终点共用同一个 x。不能把整个游标内收，
                // 否则中间刻度会产生最多 6dp 的视觉错位。
                val cursorX = trackStart + progressWidth
                // 底边两端分别裁到轨道范围：端点处留下的正好是正常等腰三角形的一半，
                // 而不是把一整条底边横移进来形成“歪顶点”的直角三角形。
                val cursorBaseLeftX = (cursorX - halfBase).coerceAtLeast(trackStart)
                val cursorBaseRightX =
                    (cursorX + halfBase).coerceAtMost(trackStart + trackWidth)

                drawRect(
                    color = trackColor,
                    topLeft = Offset(trackStart, trackTop),
                    size = Size(trackWidth, trackHeight),
                )
                if (progressWidth > 0f) {
                    drawRect(
                        color = activeColor,
                        topLeft = Offset(trackStart, trackTop),
                        size = Size(progressWidth, trackHeight),
                    )
                }

                val tipY = trackTop + trackHeight + 2.dp.toPx()
                val baseY = tipY + cursorHeight
                val cursor = Path().apply {
                    moveTo(cursorX, tipY)
                    lineTo(cursorBaseLeftX, baseY)
                    lineTo(cursorBaseRightX, baseY)
                    close()
                }
                drawPath(cursor, activeColor)
            }

            Slider(
                value = value.toFloat(),
                onValueChange = { rawValue ->
                    val minute = rawValue.roundToInt()
                        .coerceIn(MIN_CUSTOM_MINUTES, MAX_CUSTOM_MINUTES)
                    if (minute != lastHapticMinute) {
                        lastHapticMinute = minute
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onValueChange(minute)
                    }
                },
                enabled = enabled,
                valueRange = MIN_CUSTOM_MINUTES.toFloat()..MAX_CUSTOM_MINUTES.toFloat(),
                steps = MAX_CUSTOM_MINUTES - MIN_CUSTOM_MINUTES - 1,
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                    disabledThumbColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                    disabledActiveTickColor = Color.Transparent,
                    disabledInactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DURATION_SLIDER_HORIZONTAL_INSET),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DURATION_SLIDER_LABELS.forEach { minute ->
                Text(
                    text = minute.toString(),
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
private suspend fun appendImeTrace(context: Context, line: String) {
    if (!BuildConfig.DEBUG || !IME_TRACE_ENABLED) return
    withContext(Dispatchers.IO) {
        val file = File(context.filesDir, IME_TRACE_FILE)
        if (file.length() > IME_TRACE_MAX_BYTES) {
            file.writeText("")
        }
        file.appendText(line + "\n")
    }
}

// 默认关。需要诊断 IME 行为（特别是 Nothing OS 上的小跳变）时改 true，
// trace 落在 /data/data/me.excuse.app/files/reason-ime-trace.log，
// 也会同步打到 logcat tag "ReasonIme"。
private const val IME_TRACE_ENABLED = OVERLAY_DIAGNOSTICS_ENABLED
private const val IME_TRACE_FILE = "reason-ime-trace.log"
private const val IME_TRACE_MAX_BYTES = 1024 * 1024
private const val IME_CLOSE_FOCUS_CLEAR_GRACE_MS = 120L

private const val MIN_CUSTOM_MINUTES = 1
private const val MAX_CUSTOM_MINUTES = 60
private const val DEFAULT_CUSTOM_MINUTES = 20
private val CUSTOM_DURATION_CONTROL_HEIGHT = 100.dp
private val DURATION_SLIDER_TOUCH_HEIGHT = 44.dp
private val DURATION_SLIDER_HORIZONTAL_INSET = 0.dp
private val DURATION_SLIDER_LABELS = listOf(1, 15, 30, 45, 60)

// 底部按钮的行高。收放动画要预先知道这一行占多少，才能把高度平滑地收到 0。
private val BOTTOM_ACTION_HEIGHT = 56.dp

// 等 TextField 的 pointer 事件完成焦点切换后，再补发一次幂等的 IME show 请求。
// 一帧左右即可；过长会重新引入用户能察觉的键盘启动延迟。
private const val IME_FOCUS_SETTLE_BEFORE_SHOW_MS = 24L
private const val IME_OPEN_INTENT_TIMEOUT_MS = 1_000L
private const val BOTTOM_ACTIONS_RELEASE_DEBOUNCE_MS = 140L

// 理由框刚拿到焦点、旧 IME 仍在收且新一轮尚未报告可见时，等 IMM 关完重开的时长。
// 部分 ROM 关完再重开可能超过 120ms；等太短会把新一轮焦点提前清掉。
private const val IME_PENDING_REOPEN_GRACE_MS = 800L

// 理由输入框粘贴多行文本时把换行折成空格用
private val LINE_BREAK_RUN = Regex("[\\n\\r]+")
