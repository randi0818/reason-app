package me.excuse.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.excuse.app.data.db.UsageSession
import me.excuse.app.ui.theme.Danger
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.SurfaceBg
import me.excuse.app.util.TimeUtil

/**
 * @param displayStart 卡片上显示的开始时间（默认是 session 真实 startTime）。
 *                     跨日 session 在"被查询那天"的视图里会传入 clip 后的边界（比如今天的 0:00）。
 * @param displayEnd   同上，结束时间。null 时退回到当前时间或真实 endTime。
 */
@Composable
fun SessionCard(
    session: UsageSession,
    modifier: Modifier = Modifier,
    displayStart: Long = session.startTime,
    displayEnd: Long? = session.endTime
) {
    val now = System.currentTimeMillis()
    val end = displayEnd ?: now
    val durationMin = ((end - displayStart) / 60_000L).toInt().coerceAtLeast(0)

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TimeUtil.hhmm(displayStart), color = Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text("·", color = Muted)
                Spacer(Modifier.width(8.dp))
                Text(session.appName, color = Ink, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text("·", color = Muted)
                Spacer(Modifier.width(8.dp))
                Text("${durationMin}min", color = Ink, style = MaterialTheme.typography.bodyMedium)
                if (session.overran) {
                    Spacer(Modifier.width(8.dp))
                    Text("超时", color = Danger, style = MaterialTheme.typography.bodyMedium)
                }
                if (session.endTime == null) {
                    Spacer(Modifier.width(8.dp))
                    Text("使用中", color = Ink, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("「${session.reason}」", color = Ink, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
