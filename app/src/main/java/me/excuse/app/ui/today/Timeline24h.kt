package me.excuse.app.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.excuse.app.data.db.UsageSession
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Line
import me.excuse.app.ui.theme.Faint
import me.excuse.app.ui.theme.Paper
import me.excuse.app.util.TimeUtil

/**
 * Timeline24h —— 任意一天的 24 小时横向 timeline。
 *
 * @param dayStart 该天 00:00 的时间戳；null 时退回到今天 00:00（保持旧调用兼容）
 * @param nowMillis 现在的时间戳；null 时**不画"现在"白三角**（用于历史日子）
 */
@Composable
fun Timeline24h(
    sessions: List<UsageSession>,
    cursorTimestamp: Long?,
    nowMillis: Long?,
    modifier: Modifier = Modifier,
    dayStart: Long = TimeUtil.startOfTodayMillis()
) {
    val dayMillis = 24 * 60 * 60 * 1000L
    val textMeasurer = rememberTextMeasurer()

    val hourLabels = listOf(6, 9, 12, 15, 18, 21)
    val labelStyle = TextStyle(
        fontSize = 12.sp,
        color = Faint
    )
    val lineColor = Line
    val inkColor = Ink
    val paperColor = Paper

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(86.dp)                // 三角形小了，canvas 进一步收紧 100 → 86
            .padding(horizontal = 16.dp)
    ) {
        // 所有尺寸用 dp.toPx() —— 不同密度屏上视觉一致，不会在 3x 屏留大片浪费空白
        val w = size.width
        val barH = 32.dp.toPx()
        val barTop = 14.dp.toPx()          // 上方留出"现在"三角的空间
        val barBottom = barTop + barH

        // 背景灰条
        drawRect(
            color = lineColor,
            topLeft = Offset(0f, barTop),
            size = Size(w, barH)
        )

        // 使用片段 —— 每段右边留 0.5dp inset 强制可见间隙，避免短间隔挤成一坨
        val rightInset = 0.5.dp.toPx()
        val minSegmentWidth = 1.dp.toPx()
        sessions.forEach { s ->
            val start = s.startTime.coerceAtLeast(dayStart)
            // 历史日子 nowMillis = null：还在使用中的 session 退化为"截止到该天 24:00"
            val effectiveEnd = s.endTime ?: nowMillis ?: (dayStart + dayMillis)
            val end = effectiveEnd.coerceAtMost(dayStart + dayMillis)
            if (end <= start) return@forEach
            val x0 = ((start - dayStart).toFloat() / dayMillis) * w
            val x1 = ((end - dayStart).toFloat() / dayMillis) * w
            val width = (x1 - x0 - rightInset).coerceAtLeast(minSegmentWidth)
            drawRect(
                color = inkColor,
                topLeft = Offset(x0, barTop),
                size = Size(width, barH)
            )
        }

        // 「现在」游标 —— bar 上方，白色空心 + 黑色描边，朝下指
        // 历史日子（nowMillis == null）不画
        if (nowMillis != null) {
            val nowX = ((nowMillis - dayStart).toFloat() / dayMillis) * w
            // 越过今天范围（理论不会发生但防御一下）就不画
            if (nowX in 0f..w) {
                val tipY = barTop - 2.dp.toPx()
                val baseY = tipY - 6.dp.toPx()
                val halfBase = 4.dp.toPx()
                val tri = Path().apply {
                    moveTo(nowX, tipY)
                    lineTo(nowX - halfBase, baseY)
                    lineTo(nowX + halfBase, baseY)
                    close()
                }
                drawPath(tri, paperColor, style = Fill)
                drawPath(tri, inkColor, style = Stroke(width = 1.5.dp.toPx()))
            }
        }

        // 滚动游标 —— bar 下方，黑色实心，朝上指；视觉主角但不抢戏
        if (cursorTimestamp != null) {
            val cx = ((cursorTimestamp - dayStart).toFloat() / dayMillis) * w
            val tipY = barBottom + 2.dp.toPx()
            val baseY = tipY + 9.dp.toPx()
            val halfBase = 6.dp.toPx()
            val tri = Path().apply {
                moveTo(cx, tipY)
                lineTo(cx - halfBase, baseY)
                lineTo(cx + halfBase, baseY)
                close()
            }
            drawPath(tri, inkColor)
        }

        // 小时刻度文字
        val labelTopY = barBottom + 16.dp.toPx()
        hourLabels.forEach { hour ->
            drawHourLabel(textMeasurer, hour, hour / 24f * w, labelTopY, labelStyle)
        }
    }
}

private fun DrawScope.drawHourLabel(
    measurer: TextMeasurer,
    hour: Int,
    centerX: Float,
    topY: Float,
    style: TextStyle
) {
    val layout = measurer.measure(text = hour.toString(), style = style)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(centerX - layout.size.width / 2f, topY)
    )
}
