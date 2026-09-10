package me.excuse.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.time.LocalDate
import me.excuse.app.appServices
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.LocalAppColors
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.Paper
import me.excuse.app.ui.theme.SurfaceBg
import me.excuse.app.util.TimeUtil

/**
 * 月视图 —— 5 × 6 = 30 块 Live Tile 风灰度热力图。
 * 滚动 30 天，今天在右下角。
 * 阈值是绝对值（min），跨月对比稳定 —— 不会因为某周特别低就把别的周染深。
 */
@Composable
fun MonthContent(onDayClick: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    val repository = ctx.appServices.repository
    val vm: RangeViewModel = viewModel(
        key = "range-30",
        factory = RangeViewModel.Factory(repository, days = 30)
    )
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val entries by vm.entryCount.collectAsStateWithLifecycle()
    val overruns by vm.overrunCount.collectAsStateWithLifecycle()
    val reasonWall by vm.reasonWall.collectAsStateWithLifecycle()

    val nowMillis by produceState(initialValue = System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(60_000L) }
    }

    val today = remember(nowMillis) { TimeUtil.toLocalDate(nowMillis) }
    val days = remember(today) { (0..29).map { today.minusDays((29 - it).toLong()) } }

    LaunchedEffect(vm, today) {
        vm.refreshForDate(today)
    }

    // 按日期聚合分钟数 —— 跨日 session 按 clip 到每一天的可见时长分别累加。
    // 例如 5/11 23:50 → 5/12 00:10 的 20 分钟会拆成 5/11 +10min + 5/12 +10min。
    val minutesByDay = remember(sessions, nowMillis, days) {
        val map = HashMap<LocalDate, Int>()
        days.forEach { map[it] = 0 }
        days.forEach { date ->
            val dayStart = TimeUtil.startOfDayMillis(date)
            val dayEnd = TimeUtil.startOfDayMillis(date.plusDays(1))
            map[date] = usageMinutesInWindow(
                sessions = sessions,
                openSessionEndMillis = nowMillis,
                windowStartMillis = dayStart,
                windowEndMillis = dayEnd,
            )
        }
        map
    }

    val totalMillis = remember(sessions, nowMillis, days) {
        usageDurationMillisInWindow(
            sessions = sessions,
            openSessionEndMillis = nowMillis,
            windowStartMillis = TimeUtil.startOfDayMillis(days.first()),
            windowEndMillis = TimeUtil.startOfDayMillis(days.last().plusDays(1)),
        )
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Paper),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "month-summary", contentType = "summary") {
            Column {
                MonthStatsRow(duration = minuteDisplayValue(totalMillis), entries = entries, overruns = overruns)

                Spacer(Modifier.height(16.dp))

                // 6 列 × 5 行。尺寸参数来自 RangeGeometry —— 周视图的 timeline 高度由同一处算出，
                // 两个 tab 的数据块因此永远一样高。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = RangeGeometry.HorizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(RangeGeometry.CellGap)
                ) {
                    (0 until RangeGeometry.ROWS).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(RangeGeometry.CellGap)
                        ) {
                            (0 until RangeGeometry.COLUMNS).forEach { col ->
                                val idx = row * RangeGeometry.COLUMNS + col
                                val date = days[idx]
                                val minutes = minutesByDay[date] ?: 0
                                DayTile(
                                    date = date,
                                    minutes = minutes,
                                    isToday = date == today,
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clickable(onClickLabel = "查看当天记录") { onDayClick(date) }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 图例 —— 五阶灰度参考
                MonthLegend(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        reasonWall(items = reasonWall, rangeLabel = "最近 30 天")
    }
}

@Composable
private fun MonthStatsRow(duration: String, entries: Int, overruns: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        MonthStatItem(label = "时长", value = duration, unit = "min")
        MonthStatItem(label = "次数", value = entries.toString(), unit = "次")
        MonthStatItem(label = "超时", value = overruns.toString(), unit = "次")
    }
}

@Composable
private fun MonthStatItem(label: String, value: String, unit: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, style = TextStyle(fontSize = 12.sp, letterSpacing = 0.5.sp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Ink, style = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Light, lineHeight = 36.sp))
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Muted, style = TextStyle(fontSize = 12.sp), modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/** 把分钟数映射到 5 阶灰度（绝对阈值）。
 *  从主题的 heatmap ramp 取色；深色模式下 ramp 已反转，"多" = 亮，"少" = 接近背景。
 */
@Composable
@ReadOnlyComposable
private fun bucketColor(minutes: Int): Color {
    val ramp = LocalAppColors.current.heatmap
    return when {
        minutes <= 0 -> ramp[0]
        minutes < 30 -> ramp[1]
        minutes < 90 -> ramp[2]
        minutes < 180 -> ramp[3]
        else -> ramp[4]
    }
}

@Composable
private fun DayTile(
    date: LocalDate,
    minutes: Int,
    isToday: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = bucketColor(minutes)
    // 今天加一圈细黑描边（用 border modifier 不太够看 —— 用嵌套 Box 模拟内描边）
    Box(
        modifier
            .semantics {
                contentDescription = monthDayAccessibilityLabel(date, minutes, isToday)
            }
            .background(if (isToday) Ink else SurfaceBg)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(if (isToday) 1.5.dp else 0.dp)
                .background(bg)
        )
    }
}

internal fun monthDayAccessibilityLabel(
    date: LocalDate,
    minutes: Int,
    isToday: Boolean,
): String {
    val dateLabel = "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    val todayLabel = if (isToday) "，今天" else ""
    val usageLabel = if (minutes <= 0) "没有使用记录" else "使用 $minutes 分钟"
    return "$dateLabel$todayLabel，$usageLabel"
}

@Composable
private fun MonthLegend(modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "少",
            color = Muted,
            style = TextStyle(fontSize = 11.sp, letterSpacing = 0.5.sp)
        )
        LocalAppColors.current.heatmap.forEach { color ->
            Box(
                Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .background(color)
            )
        }
        Text(
            "多",
            color = Muted,
            style = TextStyle(fontSize = 11.sp, letterSpacing = 0.5.sp)
        )
    }
}
