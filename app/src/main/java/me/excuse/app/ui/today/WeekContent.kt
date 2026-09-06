package me.excuse.app.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.time.LocalDate
import me.excuse.app.appServices
import me.excuse.app.data.db.UsageSession
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Line
import me.excuse.app.ui.theme.Faint
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.Paper
import androidx.compose.material3.Text
import me.excuse.app.util.TimeUtil

@Composable
fun WeekContent(onDayClick: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    val repository = ctx.appServices.repository
    val vm: RangeViewModel = viewModel(
        key = "range-7",
        factory = RangeViewModel.Factory(repository, days = 7)
    )
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val entries by vm.entryCount.collectAsStateWithLifecycle()
    val overruns by vm.overrunCount.collectAsStateWithLifecycle()
    val reasonWall by vm.reasonWall.collectAsStateWithLifecycle()

    // 现在的时间戳 —— 用于今天那根 bar 的"现在"游标 + 还在使用中的 session 时长
    val nowMillis by produceState(initialValue = System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(60_000L) }
    }

    val today = remember(nowMillis) { TimeUtil.toLocalDate(nowMillis) }
    val days = remember(today) { (0..6).map { today.minusDays((6 - it).toLong()) } }

    LaunchedEffect(vm, today) {
        vm.refreshForDate(today)
    }

    // 把 sessions 按日期分桶 —— 跨日的 session 同时进入 startDate 和 endDate 两个桶，
    // DayBar 内部的 clip 逻辑会画对应那一天该有的那一段。
    val sessionsByDay = remember(sessions, nowMillis, days) {
        val map = HashMap<LocalDate, MutableList<UsageSession>>()
        days.forEach { map[it] = mutableListOf() }
        val windowStart = days.first()
        val windowEnd = days.last()
        sessions.forEach { s ->
            val startDay = TimeUtil.toLocalDate(s.startTime)
            val endDay = TimeUtil.toLocalDate(s.endTime ?: nowMillis)
            var d = if (startDay.isBefore(windowStart)) windowStart else startDay
            val cap = if (endDay.isAfter(windowEnd)) windowEnd else endDay
            while (!d.isAfter(cap)) {
                map[d]?.add(s)
                d = d.plusDays(1)
            }
        }
        map
    }

    // 全周总分钟 —— 先累计本周窗口内的全部毫秒，再统一换算分钟。
    // 多条不足一分钟的 session 也应在合计达到一分钟后显示出来。
    val totalMin = remember(sessions, nowMillis, days) {
        val windowStartMillis = TimeUtil.startOfDayMillis(days.first())
        val windowEndMillis = TimeUtil.startOfDayMillis(days.last().plusDays(1))
        usageMinutesInWindow(
            sessions = sessions,
            openSessionEndMillis = nowMillis,
            windowStartMillis = windowStartMillis,
            windowEndMillis = windowEndMillis,
        )
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Paper),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "week-summary", contentType = "summary") {
            Column {
                WeekStatsRow(totalMin = totalMin, entries = entries, overruns = overruns)

                Spacer(Modifier.height(16.dp))

                // timeline 高度不再写死，改用月视图方格块的同一个高度：横滑切 tab 时
                // 两边的数据块严丝合缝，不会跳一下。
                val barHeight = RangeGeometry.blockHeight()

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = RangeGeometry.HorizontalPadding),
                    verticalAlignment = Alignment.Top
                ) {
                    HourScaleColumn(
                        height = barHeight,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        days.forEach { date ->
                            DayBar(
                                date = date,
                                isToday = date == today,
                                sessions = sessionsByDay[date].orEmpty(),
                                nowMillis = nowMillis,
                                barHeight = barHeight,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onDayClick(date) }
                            )
                        }
                    }
                }
            }
        }
        reasonWall(items = reasonWall, rangeLabel = "最近 7 天")
    }
}

/**
 * 周视图左侧的小时刻度。高度由调用方传入，和 DayBar 共用 RangeGeometry.blockHeight()，
 * label 按时刻百分比纵向排布。
 * 标签和今日 timeline 横向版用同一组小时（6/9/12/15/18/21），保持视觉一致。
 */
@Composable
private fun HourScaleColumn(
    height: Dp,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 10.sp,
        color = Faint,
        letterSpacing = 0.3.sp
    )
    val hours = listOf(6, 9, 12, 15, 18, 21)

    Canvas(
        modifier = modifier
            .width(22.dp)
            .height(height)
    ) {
        val h = size.height
        val dayMin = 24f
        hours.forEach { hour ->
            val y = (hour / dayMin) * h
            val layout = textMeasurer.measure(text = hour.toString(), style = labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = size.width - layout.size.width,   // 右对齐
                    y = y - layout.size.height / 2f       // 文字垂直居中到刻度线
                )
            )
        }
    }
}

@Composable
private fun WeekStatsRow(totalMin: Int, entries: Int, overruns: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        WeekStatItem(label = "时长", value = totalMin, unit = "min")
        WeekStatItem(label = "次数", value = entries, unit = "次")
        WeekStatItem(label = "超时", value = overruns, unit = "次")
    }
}

@Composable
private fun WeekStatItem(label: String, value: Int, unit: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, style = TextStyle(fontSize = 12.sp, letterSpacing = 0.5.sp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$value", color = Ink, style = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Light, lineHeight = 36.sp))
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Muted, style = TextStyle(fontSize = 12.sp), modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/**
 * 单日 bar —— 竖直 24h timeline。
 * 顶端 0:00，底端 24:00；session 段画成黑色横切片。
 * 今天那根额外画一个 ◁ 白色三角标记"现在"。
 */
@Composable
private fun DayBar(
    date: LocalDate,
    isToday: Boolean,
    sessions: List<UsageSession>,
    nowMillis: Long,
    barHeight: Dp,
    modifier: Modifier = Modifier
) {
    val inkColor = Ink
    val paperColor = Paper
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .background(Line)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val dayMillis = 24 * 60 * 60 * 1000L
                val dayStart = TimeUtil.startOfDayMillis(date)
                val dayEndCap = dayStart + dayMillis

                val minSegmentH = 1.dp.toPx()
                val bottomInset = 0.5.dp.toPx() // 视觉间隙

                sessions.forEach { s ->
                    val start = s.startTime.coerceAtLeast(dayStart)
                    val rawEnd = s.endTime ?: nowMillis
                    val end = rawEnd.coerceAtMost(dayEndCap)
                    if (end <= start) return@forEach
                    val y0 = ((start - dayStart).toFloat() / dayMillis) * h
                    val y1 = ((end - dayStart).toFloat() / dayMillis) * h
                    val segH = (y1 - y0 - bottomInset).coerceAtLeast(minSegmentH)
                    drawRect(
                        color = inkColor,
                        topLeft = Offset(0f, y0),
                        size = Size(w, segH)
                    )
                }

                // 今天：右侧白色三角 ◁ 指向"现在"
                if (isToday) {
                    val nowY = ((nowMillis - dayStart).toFloat() / dayMillis) * h
                    if (nowY in 0f..h) {
                        val tipX = w + 1.dp.toPx()
                        val baseX = tipX + 6.dp.toPx()
                        val halfBase = 4.dp.toPx()
                        val tri = Path().apply {
                            moveTo(tipX, nowY)
                            lineTo(baseX, nowY - halfBase)
                            lineTo(baseX, nowY + halfBase)
                            close()
                        }
                        drawPath(tri, paperColor, style = Fill)
                        drawPath(tri, inkColor, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = TimeUtil.mdShort(date),
            color = if (isToday) Ink else Muted,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal,
                letterSpacing = 0.3.sp
            )
        )
    }
}
