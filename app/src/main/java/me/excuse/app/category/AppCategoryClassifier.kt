package me.excuse.app.category

import java.text.Normalizer
import java.util.Locale

/**
 * Pure Kotlin category engine. Android package metadata is normalized by the adapter before it
 * reaches this class, so classification stays deterministic and JVM-testable.
 *
 * Evidence is ranked by how close it sits to app identity:
 *
 *  1. manual override;
 *  2. curated package identity, longest matching namespace first, so a sub-brand entry beats the
 *     publisher namespace above it and `com.termux.api` inherits from `com.termux`;
 *  3. a brand word in the app label (`抖音`, `中国移动`, `giffgaff`) — near-identity, and deliberately
 *     stronger than Android's declared category;
 *  4. Android's declared category, discounted for its two catch-all buckets — see
 *     [systemCategoryScore];
 *  5. a generic category word in the label (`商城`, `视频`), which needs one agreeing package token
 *     to overturn a trustworthy declared category;
 *  6. package tokens.
 *
 * Curated packages are only worth the cost when they are verified: a package guessed from a brand
 * name is dead weight at best, and `com.ss.android.ugc.livelite` — filed here as a 抖音 video
 * variant until a device showed it now ships as 抖音商城 — is how it goes wrong. Prefer a label
 * rule that re-decides on every rename.
 *
 * Chinese app names are head-final compounds — `抖音商城` is a mall, `京东金融` is a bank — so only
 * the right-most (longest, on ties) marker in a label scores in full. Earlier markers are read as
 * publisher context and drop to [LABEL_MODIFIER_SCORE].
 */
class AppCategoryClassifier {
    fun classify(
        packageName: String,
        manualCategory: AppCategory?,
        systemCategory: AppCategory?,
        appLabel: String = "",
    ): AppClassification {
        manualCategory?.let { return AppClassification(it, AppCategorySource.MANUAL) }

        val normalizedPackage = packageName.lowercase(Locale.ROOT)
        classifyKnownPackage(normalizedPackage)?.let { category ->
            return AppClassification(category, AppCategorySource.HEURISTIC)
        }

        return classifyEvidence(normalizedPackage, appLabel, systemCategory)
    }

    /** Walks the package up segment by segment, so the most specific curated namespace wins. */
    private fun classifyKnownPackage(packageName: String): AppCategory? {
        var candidate = packageName
        while (true) {
            KNOWN_NAMESPACES[candidate]?.let { return it }
            val cut = candidate.lastIndexOf('.')
            if (cut <= 0) return null
            candidate = candidate.substring(0, cut)
        }
    }

    private fun classifyEvidence(
        packageName: String,
        appLabel: String,
        systemCategory: AppCategory?,
    ): AppClassification {
        val packageTokens = packageName
            .split(PACKAGE_SEPARATOR)
            .filterTo(HashSet()) { it.isNotEmpty() }
        val normalizedLabel = Normalizer
            .normalize(appLabel, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()
        val labelTokens = normalizedLabel
            .split(LABEL_SEPARATOR)
            .filterTo(HashSet()) { it.isNotEmpty() }

        val labelHits = SIGNAL_RULES.map { rule ->
            bestLabelHit(rule, normalizedLabel, labelTokens)
        }
        val headIndex = labelHits.indices
            .filter { labelHits[it] != null }
            .maxWithOrNull(
                compareBy<Int> { labelHits[it]!!.end }.thenBy { labelHits[it]!!.length }
            )

        val scores = mutableMapOf<AppCategory, Int>()
        systemCategory?.let { scores[it] = systemCategoryScore(it) }

        SIGNAL_RULES.forEachIndexed { index, rule ->
            val packageMatchCount = rule.packageTokens.count(packageTokens::contains)
            val packageScore = when (packageMatchCount) {
                0 -> 0
                1 -> PACKAGE_TOKEN_SCORE
                else -> PACKAGE_TOKEN_SCORE + ADDITIONAL_PACKAGE_TOKEN_SCORE
            }
            val labelScore = labelHits[index]?.let { hit ->
                if (index == headIndex) hit.score else LABEL_MODIFIER_SCORE
            } ?: 0

            if (packageScore + labelScore > 0) {
                scores[rule.category] = scores.getOrDefault(rule.category, 0) +
                    packageScore + labelScore
            }
        }

        val winner = scores.entries.maxWithOrNull(
            compareBy<Map.Entry<AppCategory, Int>> { it.value }
                .thenBy { if (it.key == systemCategory) 1 else 0 }
                .thenBy { RULE_PRIORITY[it.key] ?: Int.MIN_VALUE }
        )?.key ?: return AppClassification(AppCategory.OTHER, AppCategorySource.FALLBACK)

        val source = if (winner == systemCategory) {
            AppCategorySource.SYSTEM
        } else {
            AppCategorySource.HEURISTIC
        }
        return AppClassification(winner, source)
    }

    /**
     * Position comes from the right-most, then longest, marker this rule can find — the head noun
     * of the label. Strength comes from the strongest marker it can find, because markers of one
     * rule never contradict each other: `天翼生活` is placed by `生活` and trusted because of `天翼`.
     */
    private fun bestLabelHit(
        rule: SignalRule,
        normalizedLabel: String,
        labelTokens: Set<String>,
    ): LabelHit? {
        var best: LabelHit? = null
        fun consider(markers: Set<String>, score: Int) {
            markers.forEach { marker ->
                val end = markerEnd(marker, normalizedLabel, labelTokens)
                if (end < 0) return@forEach
                val current = best
                best = when {
                    current == null -> LabelHit(end, marker.length, score)
                    end > current.end || (end == current.end && marker.length > current.length) ->
                        LabelHit(end, marker.length, maxOf(score, current.score))

                    else -> current.copy(score = maxOf(score, current.score))
                }
            }
        }
        consider(rule.brandMarkers, LABEL_BRAND_SCORE)
        consider(rule.labelMarkers, LABEL_MARKER_SCORE)
        return best
    }

    /**
     * `ApplicationInfo.category` mostly arrives from the installing store's category hint rather
     * than the app's manifest, and two of the buckets it hands out are close to noise. On a test
     * device, `CATEGORY_SOCIAL` covered four mobile carriers, two browsers and two mail clients
     * alongside the real messengers, and `CATEGORY_PRODUCTIVITY` was the dumping ground for VPNs,
     * password managers and a wallet. Those two are scored low enough that one generic label word
     * can overturn them; the narrower buckets stay authoritative.
     */
    private fun systemCategoryScore(category: AppCategory): Int =
        if (category in WEAK_SYSTEM_CATEGORIES) {
            WEAK_SYSTEM_CATEGORY_SCORE
        } else {
            SYSTEM_CATEGORY_SCORE
        }

    /** End offset of the marker inside the label, or -1 when the marker does not apply. */
    private fun markerEnd(
        marker: String,
        normalizedLabel: String,
        labelTokens: Set<String>,
    ): Int {
        if (marker.all { it in 'a'..'z' || it in '0'..'9' } && marker !in labelTokens) return -1
        val start = normalizedLabel.lastIndexOf(marker)
        return if (start < 0) -1 else start + marker.length
    }

    private data class LabelHit(val end: Int, val length: Int, val score: Int)

    private data class KnownPackageRule(
        val category: AppCategory,
        val namespaces: Set<String>,
    )

    private data class SignalRule(
        val category: AppCategory,
        val packageTokens: Set<String>,
        /** Product or company names. Distinctive enough to outrank Android's declared category. */
        val brandMarkers: Set<String>,
        /** Generic category words. One alone must not overturn system metadata. */
        val labelMarkers: Set<String>,
    )

    companion object {
        private val PACKAGE_SEPARATOR = Regex("[._-]+")
        private val LABEL_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
        private const val LABEL_BRAND_SCORE = 8
        private const val SYSTEM_CATEGORY_SCORE = 7
        private const val LABEL_MARKER_SCORE = 5
        private const val WEAK_SYSTEM_CATEGORY_SCORE = 4
        private const val PACKAGE_TOKEN_SCORE = 3
        private const val LABEL_MODIFIER_SCORE = 2
        private const val ADDITIONAL_PACKAGE_TOKEN_SCORE = 2

        /** See [systemCategoryScore]. Android's catch-all buckets, not real evidence. */
        private val WEAK_SYSTEM_CATEGORIES = setOf(
            AppCategory.SOCIAL,
            AppCategory.PRODUCTIVITY,
        )

        private val KNOWN_PACKAGE_RULES = listOf(
            KnownPackageRule(
                AppCategory.GAME,
                setOf(
                    "com.supercell", "com.rovio", "com.zynga", "com.gameloft",
                    "com.miniclip", "com.playrix", "com.hoyoverse", "com.mihoyo",
                    "com.tencent.tmgp", "com.tencent.ig", "com.ea", "com.king",
                    "com.netmarble", "com.nexon", "com.garena", "com.roblox.client",
                    "com.mojang.minecraftpe", "com.taptap",
                    "com.valvesoftware.android.steam.community",
                ),
            ),
            KnownPackageRule(
                AppCategory.MEDIA,
                setOf(
                    "com.google.android.youtube", "com.google.android.apps.youtube.music",
                    "com.spotify.music", "tv.danmaku.bili",
                    "com.ss.android.ugc.aweme",
                    "com.ss.android.ugc.trill", "com.zhiliaoapp.musically",
                    "com.smile.gifmaker", "com.kuaishou.nebula", "com.tencent.weishi",
                    "com.netease.cloudmusic", "app.podcast.cosmos", "com.tencent.qqmusic",
                    "com.tencent.karaoke", "com.kugou.android", "cn.kuwo.player",
                    "com.ximalaya.ting.android", "com.cmcc.cmvideo",
                    "com.tencent.qqlive", "com.qiyi.video", "com.youku.phone",
                    "com.hunantv.imgo.activity", "com.netflix.mediaclient",
                    "tv.twitch.android.app", "org.videolan.vlc",
                ),
            ),
            KnownPackageRule(
                AppCategory.READING,
                setOf(
                    "com.tencent.weread", "com.faultexception.reader", "com.amazon.kindle",
                    "com.google.android.apps.books", "com.google.android.apps.magazines",
                    "com.duokan.reader", "com.qidian.qdreader", "com.dragon.read",
                    "com.chaozh.ireaderfree",
                    "com.ss.android.article.news", "com.ss.android.article.lite",
                    "com.tencent.news", "com.netease.newsreader.activity",
                    "com.sohu.newsclient", "com.ifeng.news2", "com.douban.book.reader",
                    "com.bilibili.comic",
                ),
            ),
            KnownPackageRule(
                AppCategory.SOCIAL,
                setOf(
                    "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim",
                    "com.twitter.android", "com.reddit.frontpage", "com.baidu.tieba",
                    "com.xingin.xhs", "com.zhihu.android", "com.sina.weibo",
                    "com.immomo.momo", "com.douban.frodo",
                    "com.whatsapp", "com.facebook.katana", "com.instagram.android",
                    "org.telegram.messenger", "com.discord", "com.snapchat.android",
                    "com.linkedin.android", "com.google.android.apps.messaging",
                    "jp.naver.line.android", "com.kakao.talk",
                    "org.thoughtcrime.securesms", "com.viber.voip", "com.skype.raider",
                ),
            ),
            KnownPackageRule(
                AppCategory.SHOPPING,
                setOf(
                    "com.jingdong.app.mall", "com.xunmeng.pinduoduo", "com.dangdang.buy2",
                    "com.taobao.idlefish", "com.taobao.taobao", "com.taobao.litetao",
                    "com.tmall.wireless", "com.achievo.vipshop", "com.suning.mobile.ebuy",
                    "com.shizhuang.duapp", "com.zte.zmall", "com.ebay.mobile",
                    "com.amazon.mshop.android.shopping",
                    "com.alibaba.aliexpresshd", "com.einnovation.temu",
                ),
            ),
            KnownPackageRule(
                AppCategory.PRODUCTIVITY,
                setOf(
                    "com.ss.android.lark", "com.alibaba.android.rimet", "com.tencent.wework",
                    "notion.id", "cn.ticktick.task", "cn.wps.moffice_eng",
                    "com.youdao.note", "com.evernote",
                    "com.google.android.gm", "com.google.android.calendar",
                    "com.google.android.keep", "com.tencent.androidqqmail",
                    "ch.protonmail.android", "com.openai.chatgpt",
                    "com.anthropic.claude", "com.deepseek.chat", "com.doubao.android.community",
                    "com.aliyun.tongyi", "com.moonshot.kimichat", "com.tencent.hunyuan.app.chat",
                    "com.zhipu.agent", "com.zhipuai.qingyan", "com.poe.android",
                    "ai.perplexity.app.android", "ai.perplexity.comet", "ai.qwenlm.chat.android",
                    "ai.x.grok", "ai.miromind.app", "com.google.android.apps.bard",
                    "com.google.android.apps.labs.language.tailwind", "com.google.ai.edge.gallery",
                    "com.alibaba.ailabs.tg", "com.jingdong.joyai.app",
                    "com.antgroup.zhixiaobao.android", "com.antgroup.aijk.android",
                    "com.microsoft.teams", "com.microsoft.office.outlook",
                ),
            ),
            KnownPackageRule(
                AppCategory.FINANCE,
                setOf(
                    "com.eg.android.alipaygphone", "com.unionpay", "com.chinamworld.main",
                    "com.icbc", "cmb.pb", "com.jd.jrapp", "com.antfortune.wealth",
                    "com.google.android.apps.walletnfcrel",
                    "com.hexin.plat.android", "com.eastmoney.android.berlin",
                    "co.uk.getmondo", "com.zte.wallet",
                ),
            ),
            KnownPackageRule(
                AppCategory.LIFESTYLE,
                setOf(
                    "com.autonavi.minimap", "com.baidu.baidumap", "com.tencent.map",
                    "com.mobileticket", "ctrip.android.view", "com.taobao.trip", "com.qunar",
                    "com.sdu.didi.psnger", "me.ele",
                    "com.greenpoint.android.mc10086.activity", "com.sinovatech.unicom.ui",
                    "com.ct.client", "com.giffgaffmobile.controller", "com.lebara.wallet",
                    "com.pccw.clubsim", "com.tello.ui", "com.mobillium.airalo",
                    "com.tmri.app.main", "com.lolaage.tbulu.tools",
                    "com.google.android.apps.chromecast.app",
                    "com.vivo.health", "com.heytap.health", "com.huawei.health",
                    "com.xiaomi.smarthome", "com.aliyun.iot.living",
                    "com.midea.ai.appliances", "com.sankuai.meituan", "com.meituan.android.beam",
                    "com.dianping.v1", "com.xiachufang", "com.anjuke.android.app",
                    "com.nio.fy", "com.zeekrlife.mobile",
                ),
            ),
            KnownPackageRule(
                AppCategory.TOOLS,
                setOf(
                    "com.android.chrome", "org.mozilla.firefox", "com.quark.browser",
                    "com.tencent.mtt", "com.ucmobile", "com.baidu.searchbox",
                    "com.opera.browser", "com.microsoft.emmx", "com.brave.browser",
                    "com.duckduckgo.mobile.android",
                    "com.sohu.inputmethod.sogou", "com.baidu.input", "com.iflytek.inputmethod",
                    "com.tencent.wetype",
                    "com.tailscale.ipn", "com.cloudflare.onedotonedotonedotone",
                    "com.heysocks.android", "com.termux", "org.fdroid.fdroid",
                    "com.coolapk.market", "de.rwth_aachen.phyphox",
                    "com.tencent.qqpimsecure", "com.qihoo360.mobilesafe",
                    "com.xunlei.downloadprovider", "com.baidu.netdisk",
                    "com.estrongs.android.pop",
                    "ch.protonvpn.android", "com.astrill.astrillvpn",
                    "com.fast.free.unblock.thunder.vpn", "com.vpn.free.hotspot.secure.vpnify",
                    "io.geph.android", "org.getlantern.lantern", "world.letsgo.booster.android.pro",
                    "com.google.android.apps.authenticator2", "com.azure.authenticator",
                    "io.ente.auth", "keepass2android.keepass2android", "me.zhanghai.android.files",
                    "moe.shizuku.privileged.api", "rikka.appops", "bin.mt.plus", "flar2.devcheck",
                    "org.localsend.localsend_app", "com.catchingnow.icebox",
                ),
            ),
        )

        private val KNOWN_NAMESPACES: Map<String, AppCategory> = buildMap {
            KNOWN_PACKAGE_RULES.forEach { rule ->
                rule.namespaces.forEach { namespace -> put(namespace, rule.category) }
            }
        }

        /**
         * Token matching is segment-based: `twittervideodownloader` does not equal `twitter`, and
         * publisher names such as `netease` are intentionally absent because one publisher ships
         * games, music, mail, and utilities.
         */
        private val SIGNAL_RULES = listOf(
            SignalRule(
                AppCategory.GAME,
                setOf(
                    "game", "games", "gaming", "poker", "zuma", "solitaire", "mahjong",
                    "casino", "minecraft", "roblox", "genshin", "honkai", "clash", "tmgp",
                ),
                setOf(
                    "原神", "崩坏", "王者荣耀", "和平精英", "第五人格", "蛋仔派对",
                    "minecraft", "roblox",
                ),
                setOf("游戏", "麻将", "扑克", "棋牌"),
            ),
            SignalRule(
                AppCategory.MEDIA,
                setOf(
                    "youtube", "netflix", "spotify", "bilibili", "iqiyi", "youku", "twitch",
                    "podcast", "music", "cloudmusic", "video", "media", "player", "radio",
                    "douyin", "aweme", "kuaishou", "ximalaya", "kugou", "kuwo", "tiktok",
                    "audiobook",
                ),
                setOf(
                    "抖音", "哔哩", "快手", "喜马拉雅", "酷狗", "酷我", "腾讯视频",
                    "爱奇艺", "优酷", "芒果tv", "youtube", "netflix", "spotify", "bilibili",
                    "tiktok",
                ),
                setOf(
                    "音乐", "视频", "播客", "电台", "影视", "直播", "短视频", "播放器",
                    "音频", "k歌", "追剧", "听书",
                ),
            ),
            SignalRule(
                AppCategory.READING,
                setOf(
                    "reader", "reading", "read", "weread", "ebook", "ebooks", "book",
                    "books", "novel", "news", "magazine", "comic", "rss", "kindle", "qidian",
                ),
                setOf("微信读书", "起点", "掌阅", "番茄小说", "今日头条", "kindle"),
                setOf(
                    "阅读", "读书", "小说", "电子书", "新闻", "资讯", "书城", "书架",
                    "漫画", "杂志", "reader",
                ),
            ),
            SignalRule(
                AppCategory.SOCIAL,
                setOf(
                    "whatsapp", "facebook", "instagram", "twitter", "wechat", "weixin",
                    "telegram", "discord", "reddit", "linkedin", "snapchat", "threads",
                    "weibo", "tieba", "zhihu", "xhs", "momo", "social", "community",
                    "forum", "chat",
                ),
                setOf(
                    "微信", "微博", "小红书", "知乎", "贴吧", "陌陌", "豆瓣", "脉脉",
                    "telegram", "discord", "reddit", "instagram", "twitter", "whatsapp",
                    "facebook", "snapchat",
                ),
                setOf("社交", "社区", "论坛", "聊天", "交友", "私信"),
            ),
            SignalRule(
                AppCategory.SHOPPING,
                setOf(
                    "amazon", "aliexpress", "taobao", "tmall", "pinduoduo", "shopping",
                    "shop", "mall", "retail", "ebay", "temu", "shein", "jingdong",
                    "idlefish", "dangdang", "vipshop", "suning", "zmall",
                ),
                setOf(
                    "淘宝", "天猫", "京东", "拼多多", "闲鱼", "当当", "唯品会", "得物",
                    "苏宁", "amazon", "temu", "shein", "ebay",
                ),
                setOf(
                    "商城", "购物", "电商", "折扣", "超市", "二手", "特卖", "拼团", "商店",
                ),
            ),
            SignalRule(
                AppCategory.PRODUCTIVITY,
                setOf(
                    "notion", "task", "calendar", "mail", "office", "docs", "drive", "note",
                    "productivity", "chatgpt", "claude", "deepseek", "qwen",
                    "grok", "kimi", "doubao", "tongyi", "perplexity",
                ),
                setOf(
                    "飞书", "钉钉", "企业微信", "石墨", "语雀", "印象笔记", "有道云笔记",
                    "滴答清单", "notion", "wps", "chatgpt", "claude", "deepseek", "豆包",
                    "通义", "千问", "kimi", "文心", "元宝", "智谱", "清言", "grok",
                    "perplexity", "gmail", "outlook",
                ),
                setOf(
                    "邮箱", "邮件", "笔记", "日历", "待办", "文档", "办公", "会议",
                    "表格", "演示", "协作",
                ),
            ),
            SignalRule(
                AppCategory.FINANCE,
                setOf(
                    "bank", "banking", "wallet", "finance", "stock", "trading", "crypto",
                    "insurance", "alipay", "unionpay", "payment", "budget", "accounting",
                ),
                setOf("支付宝", "云闪付", "同花顺", "东方财富"),
                setOf(
                    "银行", "金融", "钱包", "证券", "基金", "保险", "支付", "理财",
                    "信用卡", "记账", "股票", "贷款",
                ),
            ),
            SignalRule(
                AppCategory.LIFESTYLE,
                setOf(
                    "health", "fitness", "maps", "navigation", "weather", "travel", "ticket",
                    "smarthome", "meituan", "dianping", "food", "recipe", "delivery", "taxi",
                    "hotel", "restaurant", "medical", "unicom", "chinamobile", "chinatelecom",
                    "ctrip", "didi", "eleme", "esim", "airalo", "lebara",
                ),
                setOf(
                    "中国移动", "中国联通", "中国电信", "天翼", "营业厅",
                    "giffgaff", "mylebara", "lebara", "airalo", "club sim",
                    "美团", "大众点评", "下厨房", "米家", "高德", "百度地图",
                    "携程", "飞猪", "滴滴", "饿了么",
                ),
                setOf(
                    "健康", "运动", "地图", "导航", "天气", "铁路", "航旅", "汽车",
                    "智能家居", "外卖", "出行", "打车", "酒店", "旅行", "健身", "医疗",
                    "缴费", "快递", "生活", "esim", "运营商", "话费", "流量",
                    "套餐", "手机卡",
                ),
            ),
            SignalRule(
                AppCategory.TOOLS,
                setOf(
                    "tools", "utility", "calculator", "filemanager", "cleaner", "browser",
                    "launcher", "keyboard", "scanner", "vpn", "proxy", "authenticator",
                    "netdisk", "compass", "recorder", "clock", "devcheck", "appops", "shizuku",
                ),
                setOf("百度网盘", "迅雷"),
                setOf(
                    "浏览器", "计算器", "文件管理", "指南针", "录音", "验证器", "vpn",
                    "翻译", "输入法", "应用商店", "应用市场", "相机", "相册", "清理",
                    "扫描", "下载", "网盘", "云盘", "杀毒", "管家", "解压", "设置", "工具",
                ),
            ),
        )

        private val RULE_PRIORITY: Map<AppCategory, Int> = SIGNAL_RULES
            .mapIndexed { index, rule -> rule.category to -index }
            .toMap()
    }
}
