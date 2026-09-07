package me.excuse.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.excuse.app.ui.settings.GuideSection
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.Paper

@Composable
fun PrivacyIntroScreen(
    onContinue: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = Paper) {
        // 首启不在 AppNav 的 Scaffold 内，需自行避开系统栏。
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp)
                    .padding(top = 8.dp, bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Text(
                    text = "隐私",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                    color = Ink,
                    lineHeight = 64.sp
                )

                // 标签带句号是有意的：使用说明页的标签是话题，这四条是承诺，
                // 句号让它们读成断言而不是分类名；第二条末尾的逗号牵到第三条，
                // 让声明与例外在收起状态就连在一起。
                // 四节默认全收起是有意的：四行陈述句连起来本身就是完整一段话，
                // 收起状态也能把隐私承诺说完，展开只补充细节。
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GuideSection(
                        label = "它不联网。",
                        initiallyExpanded = false,
                        body = "这个 app 没有网络权限，也没有服务器。你可以断网用用看，功能完全一样。"
                    )
                    GuideSection(
                        label = "记录只在这台手机上，",
                        initiallyExpanded = false,
                        body = "理由、时长和名单都只存在这台手机里。没有账号，不做云同步，系统云备份也不包含这些数据。卸载应用或清除数据后，这些数据会被删除。"
                    )
                    GuideSection(
                        label = "除非你自己导出。",
                        initiallyExpanded = false,
                        body = "设置里可以把全部记录导成一个文件，存到你选的位置。导出之后它就归你保管了——里面有你写过的每一条理由，发给别人之前先看一眼。导出文件可以自行留存，但目前不能在 app 内导入恢复。"
                    )
                    GuideSection(
                        label = "它看不见你在里面做什么。",
                        initiallyExpanded = false,
                        body = "它只知道你在用哪个 app、什么时候切换，看不到屏幕内容、聊天和输入。理由窗禁止截屏和录屏；常驻通知不显示你正在用哪个 app，锁屏上也看不出来。"
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "接下来要开三个权限，都是运行必需的。",
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = Muted
                    )
                    Button(
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper)
                    ) {
                        Text("知道了，去开权限")
                    }
                    Text(
                        text = "代码开源（MIT）。",
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = Muted
                    )
                }
            }
        }
    }
}
