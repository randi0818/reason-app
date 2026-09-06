package me.excuse.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.excuse.app.data.ReasonWallItem
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Line
import me.excuse.app.ui.theme.Muted

/**
 * 理由墙。周视图和月视图共用同一份渲染，只有副标题的时间范围不同 —— 聚合口径、
 * 排序和空态都必须一致，否则同一批理由在两个 tab 里长得不一样，反而让人怀疑数据。
 *
 * 数据来自 [RangeViewModel.reasonWall]，它本身就是按 days 参数滚动取的，
 * 所以这里只负责画，不做任何过滤或再排序。
 */
internal fun LazyListScope.reasonWall(
    items: List<ReasonWallItem>,
    rangeLabel: String,
) {
    item(key = "reason-wall-header", contentType = "reason-header") {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 32.dp, end = 16.dp)) {
            Text(
                "理由墙",
                color = Ink,
                style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Light),
            )
            Text(
                rangeLabel,
                color = Muted,
                style = TextStyle(fontSize = 12.sp, letterSpacing = 0.5.sp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (items.isEmpty()) {
        item(key = "reason-wall-empty", contentType = "reason-empty") {
            Text(
                "还没有写下理由",
                color = Muted,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Light),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    // 必须把条目交给页面的同一个 LazyColumn；在一个 item 内 forEach 仍会一次排版整面墙。
    itemsIndexed(
        items = items,
        key = { _, item -> "reason-wall:${item.reason}" },
        contentType = { _, _ -> "reason" },
    ) { index, item ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (index > 0) Spacer(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.width(58.dp)) {
                    Text(
                        "${item.count}",
                        color = Ink,
                        style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light),
                    )
                    Text("次", color = Muted, style = TextStyle(fontSize = 11.sp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        item.reason,
                        color = Ink,
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Light),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.appNames.joinToString(" · "),
                        color = Muted,
                        style = TextStyle(fontSize = 12.sp),
                    )
                }
            }
        }
    }
}
