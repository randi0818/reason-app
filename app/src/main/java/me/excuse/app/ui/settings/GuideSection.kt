package me.excuse.app.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted

/**
 * 说明页的一节。整行可点，右边 +/− 是唯一的展开提示。
 *
 * 标题行的内边距必须恒定：clickable 在 padding 之前，涟漪范围就是这个含内边距的盒子，
 * 内边距随展开状态变的话，展开那一下和收回那一下的涟漪大小会不一样。
 * 所以节与节之间的呼吸交给父 Column 的 spacedBy，这里只留固定的 14dp（和名单页的组标题同值）。
 * 高度走 animateContentSize，展开不是一帧把下面的内容顶开。
 */
@Composable
internal fun GuideSection(
    label: String,
    body: String,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable(label) { mutableStateOf(initiallyExpanded) }

    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 直接用名单页 CategoryHeader 的同一个 token（18sp Medium），不要在这里重抄一遍数值 ——
            // 两处是同一种东西（可折叠的组标题），字号必须一起变，不能各漂各的。
            // 不加 letterSpacing：拉开字距是拉丁小标签的做法，汉字本来就是等宽方块，
            // 再拉开会从"一个词"散成"几个孤立的字"。
            Text(
                text = label,
                color = Ink,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (expanded) "−" else "+",
                color = Muted,
                style = TextStyle(fontSize = 14.sp)
            )
        }
        if (expanded) {
            Text(
                text = body,
                color = Ink,
                modifier = Modifier.padding(bottom = 10.dp),
                style = TextStyle(
                    fontSize = 15.sp,
                    lineHeight = 24.sp
                )
            )
        }
    }
}
