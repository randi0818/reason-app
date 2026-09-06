package me.excuse.app.ui.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.excuse.app.appServices
import java.time.LocalDate
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.Paper
import me.excuse.app.util.TimeUtil

@Composable
fun TodayScreen(onGoToMonitored: () -> Unit) {
    // 从周/月视图点进某一天的详情，放在最外层 —— 不论用户在哪个 tab 都能 push 同一个 DayDetailContent
    var drillDate by remember { mutableStateOf<LocalDate?>(null) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val today by produceState(initialValue = TimeUtil.today()) {
        while (true) {
            value = TimeUtil.today()
            delay(60_000L)
        }
    }

    // 系统返回（导航条 / 全面屏手势）在 drill-in 时退回 list，不要 finish Activity
    BackHandler(enabled = drillDate != null) {
        drillDate = null
    }

    Surface(Modifier.fillMaxSize(), color = Paper) {
        if (drillDate != null) {
            Column {
                DayDetailHeader(
                    date = drillDate!!,
                    onBack = { drillDate = null }
                )
                DayDetailContent(
                    date = drillDate!!,
                    today = today,
                    onGoToMonitored = onGoToMonitored,
                )
            }
            return@Surface
        }

        // 顶部 tab + 横向 pager 联动：点 tab 滚动到对应页；滑动 pager 反向更新 tab 高亮
        Column {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Paper,
                contentColor = Ink
            ) {
                listOf("今日", "本周", "本月").forEachIndexed { i, label ->
                    Tab(
                        selected = pagerState.currentPage == i,
                        onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                        text = { Text(label) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> DayDetailContent(
                        date = today,
                        today = today,
                        onGoToMonitored = onGoToMonitored,
                    )
                    1 -> WeekContent(onDayClick = { drillDate = it })
                    else -> MonthContent(onDayClick = { drillDate = it })
                }
            }
        }
    }
}

@Composable
private fun DayDetailHeader(date: LocalDate, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "← 返回",
            color = Muted,
            style = TextStyle(fontSize = 13.sp, letterSpacing = 0.5.sp),
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(end = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        Text(
            TimeUtil.mdShort(date),
            color = Ink,
            style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Light)
        )
    }
}

/**
 * 一天的详情视图（含 stats / timeline / 卡片流）。
 * 今天 + 历史日子共享这一份代码，差异点只有"现在"游标和 60s tick。
 */
@Composable
fun DayDetailContent(
    date: LocalDate,
    today: LocalDate,
    onGoToMonitored: () -> Unit = {},
) {
    val isToday = date == today
    val ctx = LocalContext.current
    val repository = ctx.appServices.repository
    val vm: DayViewModel = viewModel(
        key = "day-${date}",
        factory = DayViewModel.Factory(repository, date)
    )
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val entries by vm.entryCount.collectAsStateWithLifecycle()
    val overruns by vm.overrunCount.collectAsStateWithLifecycle()

    // 名单空 = 还没勾过任何 app（取消勾选是直接删行）。
    // 只看空不空，不看内容 —— 否则名单每次增删都要重组整个日视图。
    // null = 还没读出来：先什么都不画，别让老用户看见引导闪一下又被换掉。
    val hasMonitoredApps: Boolean? by remember(repository) {
        repository.monitoredApps().map { it.isNotEmpty() }
    }.collectAsStateWithLifecycle(initialValue = null)

    val listState = rememberLazyListState()

    // 只有今天才需要 60s tick 推动 "现在" 游标；历史日子 nowMillis 保持 null
    val nowMillis: Long? = if (isToday) {
        val tickingNow by produceState(initialValue = System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(60_000L)
            }
        }
        tickingNow
    } else {
        null
    }

    val dayStart = remember(date) { TimeUtil.startOfDayMillis(date) }
    val dayEnd = remember(date) { TimeUtil.startOfDayMillis(date.plusDays(1)) }

    // 把每条 session 切成"这一天的可见片段"：startTime/endTime 各 coerce 到当天范围。
    // 一条跨日 session（例如 5/11 23:50 → 5/12 00:10）会同时出现在 5/11 和 5/12 的查询里
    // （感谢 DAO 改成 overlap 查询），但在各自视图里只展示属于那一天的 10 分钟。
    val fragments = remember(sessions, nowMillis, dayStart, dayEnd) {
        daySessionFragments(
            sessions, nowMillis ?: System.currentTimeMillis(), dayStart, dayEnd,
        )
    }

    val cardBottomPaddingPx = with(LocalDensity.current) { 16.dp.toPx() }
    val cursorTimestamp by remember(fragments, cardBottomPaddingPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportTop = info.viewportStartOffset
            val target = info.visibleItemsInfo.firstOrNull { item ->
                (item.offset + item.size) - viewportTop > cardBottomPaddingPx
            }
            val idx = target?.index ?: listState.firstVisibleItemIndex
            // 用 clip 后的 displayStart —— 否则跨日 session 在今天视图里的 cursor 会指到昨天的时间戳
            fragments.getOrNull(idx)?.displayStart ?: fragments.lastOrNull()?.displayStart
        }
    }

    val totalMin = remember(fragments) {
        wholeMinutes(fragments.asSequence().map { it.displayEnd - it.displayStart })
    }

    // 注意：DayDetailContent 现在被装在 HorizontalPager 的 page slot 里 ——
    // page slot 是 Box 语义，sibling 会叠在一起。所以这里自己包一层 Column
    // 让 stats+timeline 在上、卡片流在下纵向排布。
    Column(Modifier.fillMaxSize().background(Paper)) {
        StatsRow(totalMin = totalMin, entries = entries, overruns = overruns)
        Timeline24h(
            sessions = sessions,
            cursorTimestamp = cursorTimestamp,
            nowMillis = nowMillis,
            dayStart = dayStart,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (fragments.isEmpty()) {
            when {
                hasMonitoredApps == null -> Unit
                isToday && hasMonitoredApps == false -> MonitoredListGuide(onGoToMonitored)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这一天还没有记录", color = Muted)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(fragments, key = { it.session.id }) { f ->
                    SessionCard(
                        session = f.session,
                        displayStart = f.displayStart,
                        displayEnd = f.displayEnd
                    )
                }
                item(key = "trailing-spacer") {
                    Spacer(Modifier.fillParentMaxHeight(0.85f))
                }
            }
        }
    }
}

/**
 * 名单还空着时，今天这一页的空态。
 *
 * 应用启动落在「数据」页，而让它开始工作的动作（勾监控名单）在另一个 tab —— 这是新用户
 * 必然会看到的第一屏，所以这里指路。勾上第一个 app 之后它就不再出现。
 */
@Composable
private fun MonitoredListGuide(onGoToMonitored: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "还没有监控任何 app",
            color = Ink,
            style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Light)
        )
        Text(
            "去「名单」勾几个容易刷掉时间的 app。\n之后每次打开它们，会先弹一个窗问你为什么。",
            color = Muted,
            style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onGoToMonitored,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper)
        ) {
            Text("去名单勾选", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StatsRow(totalMin: Int, entries: Int, overruns: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        StatItem(label = "时长", value = totalMin, unit = "min")
        StatItem(label = "次数", value = entries, unit = "次")
        StatItem(label = "超时", value = overruns, unit = "次")
    }
}

@Composable
private fun StatItem(label: String, value: Int, unit: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = Muted,
            style = TextStyle(fontSize = 12.sp, letterSpacing = 0.5.sp)
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$value",
                color = Ink,
                style = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Light, lineHeight = 36.sp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = unit,
                color = Muted,
                style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
    }
}
