package me.excuse.app.overlay

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.excuse.app.ui.theme.Danger
import me.excuse.app.ui.theme.Disabled
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.Paper
import me.excuse.app.ui.theme.SurfaceBg

@Composable
fun TimeUpOverlay(
    appName: String,
    originalReason: String,
    actualMinutes: Int,
    plannedMinutes: Int,
    canExtend: Boolean,
    returningHome: Boolean,
    extending: Boolean,
    onExtend: () -> Unit,
    onExit: () -> Unit
) {
    val overrun = (actualMinutes - plannedMinutes).coerceAtLeast(0)
    val contentVisibility = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    val contentTransition = rememberTransition(
        transitionState = contentVisibility,
        label = "time-up-content-entry",
    )
    val contentProgress by contentTransition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = OVERLAY_ENTER_DURATION_MS,
                easing = LinearOutSlowInEasing,
            )
        },
        label = "time-up-content-progress",
    ) { visible ->
        if (visible) 1f else 0f
    }

    // Surface 首帧即不透明；只动画内容，避免到点提示也出现下层 app 透出的竞争窗口。
    Surface(
        color = Paper,
        modifier = Modifier.fillMaxSize()
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val animatedModifier = Modifier.graphicsLayer {
                alpha = contentProgress
                translationY = (1f - contentProgress) * 8.dp.toPx()
            }
            if (maxWidth > maxHeight) {
                TimeUpLandscapeContent(
                    appName = appName,
                    originalReason = originalReason,
                    actualMinutes = actualMinutes,
                    overrun = overrun,
                    canExtend = canExtend,
                    returningHome = returningHome,
                    extending = extending,
                    onExtend = onExtend,
                    onExit = onExit,
                    modifier = animatedModifier,
                )
            } else {
                TimeUpPortraitContent(
                    appName = appName,
                    originalReason = originalReason,
                    actualMinutes = actualMinutes,
                    overrun = overrun,
                    canExtend = canExtend,
                    returningHome = returningHome,
                    extending = extending,
                    onExtend = onExtend,
                    onExit = onExit,
                    modifier = animatedModifier,
                )
            }
        }
    }
}

@Composable
private fun TimeUpPortraitContent(
    appName: String,
    originalReason: String,
    actualMinutes: Int,
    overrun: Int,
    canExtend: Boolean,
    returningHome: Boolean,
    extending: Boolean,
    onExtend: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(top = 32.dp, bottom = 36.dp),
    ) {
        // 决定按钮不参与长理由的测量。只让上方内容占用剩余高度并滚动，
        // 否则理由或系统字号一变大，整列会把「退出 / 继续用」挤到屏幕外。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Spacer(Modifier.height(40.dp))

            TimeUpHeading(appName, compact = false)

            ReasonQuote(originalReason = originalReason, compact = false)

            // 数字统计块：实际 | （超时）—— Metro 大数字 + 下方小标签
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                StatBlock(
                    label = "实际使用",
                    value = actualMinutes,
                    color = Ink
                )
                if (overrun > 0) {
                    StatBlock(
                        label = "超时",
                        value = overrun,
                        color = Danger
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))

        TimeUpActions(
            canExtend = canExtend,
            returningHome = returningHome,
            extending = extending,
            onExtend = onExtend,
            onExit = onExit,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimeUpLandscapeContent(
    appName: String,
    originalReason: String,
    actualMinutes: Int,
    overrun: Int,
    canExtend: Boolean,
    returningHome: Boolean,
    extending: Boolean,
    onExtend: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility)
                    .only(WindowInsetsSides.Horizontal)
            )
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // 横屏的高度不足以继续纵向堆完整个页面。标题和理由放左栏并允许滚动，
        // 右栏把统计与操作分开；无论理由多长，两个决定按钮都固定留在可点击区域。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TimeUpHeading(appName, compact = true)
            ReasonQuote(originalReason = originalReason, compact = true)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        ) {
            // 大字号或超长数字可换行、滚动，统计不能把决定按钮挤出屏幕。
            FlowRow(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatBlock(
                    label = "实际使用",
                    value = actualMinutes,
                    color = Ink,
                    compact = true,
                )
                if (overrun > 0) {
                    StatBlock(
                        label = "超时",
                        value = overrun,
                        color = Danger,
                        compact = true,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            TimeUpActions(
                canExtend = canExtend,
                returningHome = returningHome,
                extending = extending,
                onExtend = onExtend,
                onExit = onExit,
                compact = true,
            )
        }
    }
}

@Composable
private fun TimeUpHeading(appName: String, compact: Boolean) {
    // 比输入表单略突出，保留到点提醒的分量，同时避免原先的大字压过理由内容。
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(appName, fontSize = if (compact) 13.sp else 17.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text = "时间到了",
            fontSize = if (compact) 34.sp else 48.sp,
            fontWeight = FontWeight.Light,
            color = Ink,
            lineHeight = if (compact) 40.sp else 54.sp,
        )
    }
}

@Composable
private fun ReasonQuote(
    originalReason: String,
    compact: Boolean,
) {
    val fontSize = if (compact) 28.sp else 34.sp
    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val typeface by LocalFontFamilyResolver.current.resolve(
        fontFamily = textStyle.fontFamily,
        fontWeight = FontWeight.Light,
        fontStyle = textStyle.fontStyle ?: FontStyle.Normal,
        fontSynthesis = textStyle.fontSynthesis ?: FontSynthesis.All,
    )
    // 引号的留白由实际字体决定；测量笔画左边界，避免换字体或系统字号后又错位。
    val firstLineIndent = remember(typeface, density, fontSize) {
        val paint = Paint().apply {
            this.typeface = typeface as Typeface
            textSize = with(density) { fontSize.toPx() }
        }
        val bounds = Rect()
        paint.getTextBounds("「", 0, 1, bounds)
        with(density) { (-bounds.left.toFloat()).toSp() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "当初的理由",
            fontSize = 13.sp,
            color = Muted,
            letterSpacing = 0.5.sp  // 略加字距，Metro 风格的标签感
        )
        Text(
            text = "「$originalReason」",
            fontSize = fontSize,
            fontWeight = FontWeight.Light,
            color = Ink,
            lineHeight = if (compact) 38.sp else 46.sp,
            style = textStyle.copy(
                textIndent = TextIndent(firstLine = firstLineIndent, restLine = 0.sp),
                lineBreak = LineBreak.Heading,
            ),
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimeUpActions(
    canExtend: Boolean,
    returningHome: Boolean,
    extending: Boolean,
    onExtend: () -> Unit,
    onExit: () -> Unit,
    compact: Boolean = false,
) {
    val busy = returningHome || extending
    val contentPadding = if (compact) {
        PaddingValues(horizontal = 8.dp)
    } else {
        ButtonDefaults.ContentPadding
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (!canExtend) {
            Text(
                text = "延期已用完",
                fontSize = 12.sp,
                color = Muted,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(4.dp))
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = if (compact) 1 else 2,
        ) {
            Button(
                onClick = onExit,
                enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                shape = RoundedCornerShape(0.dp),
                contentPadding = contentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink,
                    contentColor = Paper,
                    disabledContainerColor = SurfaceBg,
                    disabledContentColor = Muted,
                )
            ) {
                Text(
                    text = if (returningHome) "正在退出…" else "退出",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (canExtend) {
                OutlinedButton(
                    onClick = onExtend,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(0.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (busy) Disabled else Ink),
                    contentPadding = contentPadding,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SurfaceBg,
                        contentColor = Ink,
                        disabledContainerColor = SurfaceBg,
                        disabledContentColor = Muted,
                    )
                ) {
                    Text(if (extending) "正在继续…" else "+5 分钟", fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: Int, color: Color, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = color.copy(alpha = 0.55f),
            letterSpacing = 0.5.sp
        )
        // 不同字号的文字底部留白不同，用文字基线对齐，避免单位随字号下沉。
        Row {
            Text(
                text = "$value",
                fontSize = if (compact) 36.sp else 42.sp,
                fontWeight = FontWeight.Light,
                color = color,
                lineHeight = if (compact) 40.sp else 46.sp,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "min",
                fontSize = 14.sp,
                color = color.copy(alpha = 0.55f),
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}
