package me.excuse.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.excuse.app.BuildConfig
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.Paper

/**
 * 给"未来的你 / 第一次接触的朋友"看的简短说明。
 * 不是产品介绍 —— 是「碰到 X 的时候我该期待什么」的小本本。
 *
 * 各节结构一样，区别是默认展开还是收起：
 * - 前两节是建立使用预期的入门，默认展开。
 * - 其余章节是"带着问题回来查"的，默认收起，标题就是索引。
 *
 * 曾经上半用空白分节、下半用横线分节，两套语言混在一页，看着乱。现在只有一种。
 * 不画分隔线是因为 +/− 已经说明了这行可以展开，再加一条线就是重复表达同一件事 ——
 * 名单页的 CategoryHeader 早就是这个样子，两处保持一致。
 *
 * 这里写的每一条都对应一段真实实现（时序常量、状态机分支、聚合口径）。
 * 改行为的时候顺手回来对一遍，别让它变成考古现场。
 */
@Composable
fun UsageGuideScreen(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.fillMaxSize()) {
            // 顶部返回
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    "← 返回",
                    color = Muted,
                    style = TextStyle(fontSize = 13.sp, letterSpacing = 0.5.sp),
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp)
                    .padding(top = 8.dp, bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                // 大标题
                Text(
                    text = "说明",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                    color = Ink,
                    lineHeight = 64.sp
                )

                // 展开状态各自独立 —— 想对照两节的时候不该互相挤掉；
                // rememberSaveable 让转屏后还停在原处。
                // 节间距放在这里而不是行的内边距里，是为了让涟漪盒子恒定，见 GuideSection。
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GuideSection(
                        label = "它是做什么的",
                        initiallyExpanded = true,
                        body = "在你打开抖音、微信、小红书这类容易刷掉一整段时间的 app 之前，弹出一个窗口，" +
                                "请你用文字写下「我为什么要用它」，再选一个使用时长。" +
                                "目的不是拦着你，是让你停一秒，听见自己在做什么。"
                    )

                    GuideSection(
                        label = "怎么用",
                        initiallyExpanded = true,
                        body = "1. 在「名单」页勾选要监控的 app，勾完立即生效。\n" +
                                "2. 开始一次新的使用时会先弹拦截窗：写理由、选时长" +
                                "（5 / 10 / 15 / 30 分钟，或者「自定义」拖 1–60 分钟），点「确认」。\n" +
                                "3. 进入之后通知栏显示「正在计时 · 剩余 N 分钟」，每分钟刷新一次。\n" +
                                "4. 时间到再弹一次，可以「继续用 +5 分钟」或者「退出」。\n" +
                                "5. 在「数据」页回看今天 / 最近 7 天 / 最近 30 天的理由和时长。"
                    )

                    GuideSection(
                        label = "短暂切走，再回来",
                        body = "· 使用中短暂切到桌面、最近任务或未监控的 app，10 秒内回到原 app，" +
                                "只要还没到所选时长，就接着用，不用重新写理由。切走期间剩余时间仍会减少。\n" +
                                "· 离开超过 10 秒，本次使用结束；再打开需重新填写理由。最后这段等待不会计入使用时长。\n" +
                                "· 关屏或切到另一个受监控的 app，会立即结束本次使用；暂停监控、移出名单或失去必要权限也会结束。\n" +
                                "· 如果切走期间已到所选时长，本次使用也会结束；不会追到桌面或其它 app 上弹窗，回来时重新写理由。"
                    )

                    GuideSection(
                        label = "几条不那么显然的规则",
                        body = "· 理由可以重复。同一个 app 同一天里再写一遍旧理由，只会在输入框下面提示" +
                                "「今天第 N 次用这个理由」，照样能确认 —— 让你自己看见，不拦你。\n" +
                                "· 拦截窗不会因为按 Home 或划掉目标 app 而消失；10 秒不碰它就会请求返回桌面，" +
                                "退出完成后关窗。打字或者点时长之后，这个倒计时重置成 20 秒。\n" +
                                "· 点「我不用了」和超时的结果一样：返回桌面后关窗。若系统未能完成退出，会重新显示理由窗。再打开还会拦。\n" +
                                "· 延期最多一次，+5 分钟。用掉之后第二次到点就只剩「退出」，窗口上会写「延期已用完」。"
                    )

                    GuideSection(
                        label = "名单页",
                        body = "app 按分类折叠成组，点组标题展开；顶部可以搜索 app 名。\n" +
                                "系统应用默认不列出来，需要的话打开「显示系统 app」。\n" +
                                "分类是猜出来的，偶尔会认错 —— 长按一行可以手动改它的分类，或者改回「跟随系统」。"
                    )

                    GuideSection(
                        label = "数据视图怎么看",
                        body = "「今日」「本周」「本月」三个 tab 可以横滑切换。「本周」是最近 7 天、" +
                                "「本月」是最近 30 天 —— 都是滚动窗口，不是自然周和自然月。\n" +
                                "顶部三个数：时长（这段时间的总分钟）、次数（确认进入的次数）、" +
                                "超时（其中用过「+5 分钟」的次数）。\n" +
                                "今日 timeline 上方的空心三角是「现在」，下方的实心三角随卡片流移动。\n" +
                                "最近 7 天是 7 根竖向 timeline，最右是今天；最近 30 天是 6 × 5 的灰度方块，" +
                                "今天在右下角。竖条和方块都可以点开看那天的详情。\n" +
                                "方块深浅按每日所有受监控应用的累计时长分五档：0、1–29、30–89、90–179、180 分钟及以上。" +
                                "分档固定，不随当月最高值变化，" +
                                "下面有「少 → 多」的图例。\n" +
                                "「本周」「本月」往下滚还有一面理由墙：这段时间写过的理由按次数排，" +
                                "每条下面列出用过它的 app。"
                    )

                    GuideSection(
                        label = "如果某天它不工作了",
                        body = "首先去「设置 → 权限与自启动设置」检查自启动或电池策略 —— " +
                                "国产 ROM 可能限制后台监控。\n" +
                                "小米 / Redmi 请分别检查「省电策略」与「自启动」：在本应用的应用信息中，" +
                                "进入「省电策略」选择「无限制」，再单独检查自启动。常驻通知还在也可能暂停监控。\n" +
                                "如果拦截弹不出来，去系统权限页确认「使用情况访问」和「悬浮窗」两个权限还在；" +
                                "Android 13 以上还需要通知权限，前台服务靠常驻通知活着。\n" +
                                "权限被系统重置过，也可以从同一个入口补回来。"
                    )

                    GuideSection(
                        label = "关于隐私",
                        body = "所有数据存在本机的 SQLite 数据库里。\n" +
                                "本应用没有声明网络权限 —— 它自己不能把数据发到网上。\n" +
                                "不会上报使用情况、不会接入任何第三方分析。\n" +
                                "常驻通知只写「正在计时 · 剩余 N 分钟」，不显示是哪个 app，锁屏上也看不到。"
                    )

                    GuideSection(
                        label = "本机存了些什么",
                        body = "两类东西：\n" +
                                "· 每一次进入的记录 —— 你写的理由、选的时长、起止时间。「数据」页看到的就是它。\n" +
                                "· 每一次弹窗的结果 —— 进入 / 你点了「我不用了」/ 超时自动关掉。" +
                                "这一类不在界面上展示，但确实记了下来；它是唯一能说明「拦下过多少次」的数据。\n" +
                                "另外还有你勾的监控名单和手动改过的分类。"
                    )

                    GuideSection(
                        label = "管理记录",
                        body = "从「设置 → 管理记录」进入。记录默认全部保留，不会自动清理。\n" +
                                "点「导出全部记录」，保存当前保留的使用记录、弹窗结果、监控名单和手动分类为 JSON 文件。" +
                                "文件由你自己保管，暂不支持导入恢复。\n" +
                                "清理时先选择日期，" +
                                "预览使用记录和弹窗结果的数量，再点「永久清理」。" +
                                "初始日期是三个月前，只是预选值；最多选到今天，所选日期当天不删。\n" +
                                "跨过该日期、恰在当天零点结束或尚未结束的使用记录及关联弹窗结果会保留，名单和分类不受影响。" +
                                "清理会永久删除理由、减少相关统计，建议先导出留存；JSON 不能在应用内恢复。\n" +
                                "删除后的空间可供新记录复用，系统显示的占用可能不会立即变小。"
                    )

                    GuideSection(
                        label = "切换深色模式",
                        body = "「设置」页的「主题」一行可以选 跟随系统 / 浅色 / 深色，默认浅色。\n" +
                                "切换立即生效，下次启动保持。\n" +
                                "拦截窗、超时窗、热力图都会跟随；深色下热力图反转 —— 越亮 = 那天用得越久。"
                    )
                }

                // 没有分隔线可以把页脚分出来，改用一段明显大于行距的留白。
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = Muted,
                    modifier = Modifier.padding(top = 16.dp),
                    style = TextStyle(
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    )
                )
            }
        }
    }
}
