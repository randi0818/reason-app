package me.excuse.app.category

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCategoryClassifierTest {
    private val classifier = AppCategoryClassifier()

    @Test
    fun `manual category wins over system and package heuristic`() {
        val result = classifier.classify(
            packageName = "com.supercell.game",
            manualCategory = AppCategory.SHOPPING,
            systemCategory = AppCategory.SOCIAL
        )

        assertEquals(AppCategory.SHOPPING, result.category)
        assertEquals(AppCategorySource.MANUAL, result.source)
    }

    @Test
    fun `system category wins over package heuristic`() {
        val result = classifier.classify(
            packageName = "com.example.poker",
            manualCategory = null,
            systemCategory = AppCategory.TOOLS
        )

        assertEquals(AppCategory.TOOLS, result.category)
        assertEquals(AppCategorySource.SYSTEM, result.source)
    }

    @Test
    fun `package heuristic is used when stronger evidence is absent`() {
        val result = classifier.classify(
            packageName = "com.example.solitaire",
            manualCategory = null,
            systemCategory = null
        )

        assertEquals(AppCategory.GAME, result.category)
        assertEquals(AppCategorySource.HEURISTIC, result.source)
    }

    @Test
    fun `unknown package falls back to other`() {
        val result = classifier.classify(
            packageName = "org.example.unknown",
            manualCategory = null,
            systemCategory = null
        )

        assertEquals(AppCategory.OTHER, result.category)
        assertEquals(AppCategorySource.FALLBACK, result.source)
    }

    @Test
    fun `clearing manual category restores system category`() {
        val manuallyClassified = classifier.classify(
            packageName = "com.example.app",
            manualCategory = AppCategory.SHOPPING,
            systemCategory = AppCategory.SOCIAL
        )
        val afterClear = classifier.classify(
            packageName = "com.example.app",
            manualCategory = null,
            systemCategory = AppCategory.SOCIAL
        )

        assertEquals(AppCategory.SHOPPING, manuallyClassified.category)
        assertEquals(AppCategory.SOCIAL, afterClear.category)
        assertEquals(AppCategorySource.SYSTEM, afterClear.source)
    }

    @Test
    fun `known package corrects misleading system category`() {
        val result = classifier.classify(
            packageName = "com.netease.cloudmusic",
            manualCategory = null,
            systemCategory = AppCategory.GAME,
            appLabel = "网易云音乐",
        )

        assertEquals(AppCategory.MEDIA, result.category)
        assertEquals(AppCategorySource.HEURISTIC, result.source)
    }

    @Test
    fun `video and reading apps are classified separately`() {
        val video = classifier.classify("com.google.android.youtube", null, null)
        val reading = classifier.classify("com.tencent.weread", null, AppCategory.MEDIA)

        assertEquals(AppCategory.MEDIA, video.category)
        assertEquals(AppCategory.READING, reading.category)
        assertEquals(AppCategorySource.HEURISTIC, reading.source)
    }

    @Test
    fun `reading label is not absorbed by media signals`() {
        val result = classifier.classify(
            packageName = "org.example.opaque",
            manualCategory = null,
            systemCategory = null,
            appLabel = "每日阅读",
        )

        assertEquals(AppCategory.READING, result.category)
    }

    @Test
    fun `agreeing package and label evidence can correct system metadata`() {
        val result = classifier.classify(
            packageName = "org.example.reader",
            manualCategory = null,
            systemCategory = AppCategory.MEDIA,
            appLabel = "每日阅读",
        )

        assertEquals(AppCategory.READING, result.category)
        assertEquals(AppCategorySource.HEURISTIC, result.source)
    }

    @Test
    fun `one weak label does not overrule system metadata`() {
        val result = classifier.classify(
            packageName = "org.example.opaque",
            manualCategory = null,
            systemCategory = AppCategory.TOOLS,
            appLabel = "每日阅读",
        )

        assertEquals(AppCategory.TOOLS, result.category)
        assertEquals(AppCategorySource.SYSTEM, result.source)
    }

    @Test
    fun `label evidence beats a conflicting package token without system metadata`() {
        val result = classifier.classify(
            packageName = "org.example.video",
            manualCategory = null,
            systemCategory = null,
            appLabel = "每日阅读",
        )

        assertEquals(AppCategory.READING, result.category)
    }

    @Test
    fun `latin label markers match whole words after unicode normalization`() {
        val normalized = classifier.classify(
            packageName = "org.example.opaque",
            manualCategory = null,
            systemCategory = null,
            appLabel = "Ｎｅｔｆｌｉｘ Player",
        )
        val substringOnly = classifier.classify(
            packageName = "org.example.opaque",
            manualCategory = null,
            systemCategory = null,
            appLabel = "Threader",
        )

        assertEquals(AppCategory.MEDIA, normalized.category)
        assertEquals(AppCategory.OTHER, substringOnly.category)
    }

    @Test
    fun `opaque Chinese package names use curated identities`() {
        val expectations = mapOf(
            "com.tencent.mm" to AppCategory.SOCIAL,
            "com.tencent.mobileqq" to AppCategory.SOCIAL,
            "com.ss.android.ugc.aweme" to AppCategory.MEDIA,
            "com.xingin.xhs" to AppCategory.SOCIAL,
            "com.sankuai.meituan" to AppCategory.LIFESTYLE,
            "com.eg.android.AlipayGphone" to AppCategory.FINANCE,
            "com.openai.chatgpt" to AppCategory.PRODUCTIVITY,
        )

        expectations.forEach { (packageName, expectedCategory) ->
            assertEquals(
                packageName,
                expectedCategory,
                classifier.classify(packageName, null, null).category,
            )
        }
    }

    @Test
    fun `localized app label helps when package is opaque`() {
        val result = classifier.classify(
            packageName = "org.example.opaque",
            manualCategory = null,
            systemCategory = null,
            appLabel = "小红书",
        )

        assertEquals(AppCategory.SOCIAL, result.category)
        assertEquals(AppCategorySource.HEURISTIC, result.source)
    }

    @Test
    fun `package token matching does not use arbitrary substrings`() {
        val result = classifier.classify(
            packageName = "tweeter.gif.twittervideodownloader",
            manualCategory = null,
            systemCategory = null,
        )

        assertEquals(AppCategory.OTHER, result.category)
        assertEquals(AppCategorySource.FALLBACK, result.source)
    }

    @Test
    fun `publisher name alone does not imply game`() {
        val result = classifier.classify(
            packageName = "com.netease.mail",
            manualCategory = null,
            systemCategory = null,
        )

        assertEquals(AppCategory.PRODUCTIVITY, result.category)
        assertEquals(AppCategorySource.HEURISTIC, result.source)
    }

    @Test
    fun `a renamed sibling package is decided by its label, not its namespace`() {
        // com.ss.android.ugc.livelite ships as 抖音商城; it used to be a 抖音 video variant.
        val mall = classifier.classify(
            packageName = "com.ss.android.ugc.livelite",
            manualCategory = null,
            systemCategory = null,
            appLabel = "抖音商城",
        )
        val lite = classifier.classify(
            packageName = "com.ss.android.ugc.aweme.lite",
            manualCategory = null,
            systemCategory = null,
            appLabel = "抖音极速版",
        )

        assertEquals(AppCategory.SHOPPING, mall.category)
        assertEquals(AppCategory.MEDIA, lite.category)
    }

    @Test
    fun `curated namespaces cover their descendants`() {
        val expectations = mapOf(
            "com.termux" to AppCategory.TOOLS,
            "com.termux.api" to AppCategory.TOOLS,
            "com.termux.boot" to AppCategory.TOOLS,
            "com.tencent.tmgp.sgame" to AppCategory.GAME,
        )

        expectations.forEach { (packageName, expectedCategory) ->
            assertEquals(
                packageName,
                expectedCategory,
                classifier.classify(packageName, null, null).category,
            )
        }
    }

    @Test
    fun `carrier apps survive the social hint the store stamps on them`() {
        val curated = classifier.classify(
            packageName = "com.giffgaffmobile.controller",
            manualCategory = null,
            systemCategory = AppCategory.SOCIAL,
            appLabel = "giffgaff",
        )
        val uncurated = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = null,
            systemCategory = AppCategory.SOCIAL,
            appLabel = "Club Sim",
        )

        assertEquals(AppCategory.LIFESTYLE, curated.category)
        assertEquals(AppCategory.LIFESTYLE, uncurated.category)
    }

    @Test
    fun `only the catch-all system buckets yield to a single label word`() {
        val weak = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = null,
            systemCategory = AppCategory.PRODUCTIVITY,
            appLabel = "阅读",
        )
        val trusted = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = null,
            systemCategory = AppCategory.MEDIA,
            appLabel = "阅读",
        )

        assertEquals(AppCategory.READING, weak.category)
        assertEquals(AppCategory.MEDIA, trusted.category)
    }

    @Test
    fun `head noun outranks the brand it is prefixed with`() {
        val mall = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = null,
            systemCategory = null,
            appLabel = "抖音商城",
        )
        val finance = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = null,
            systemCategory = null,
            appLabel = "京东金融",
        )
        val reading = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = null,
            systemCategory = null,
            appLabel = "微信读书",
        )

        assertEquals(AppCategory.SHOPPING, mall.category)
        assertEquals(AppCategory.FINANCE, finance.category)
        assertEquals(AppCategory.READING, reading.category)
    }

    @Test
    fun `longer head marker wins when two markers end together`() {
        val result = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = null,
            systemCategory = null,
            appLabel = "企业微信",
        )

        assertEquals(AppCategory.PRODUCTIVITY, result.category)
    }

    @Test
    fun `brand label overrules a wrong declared category`() {
        val mobile = classifier.classify(
            packageName = "com.greenpoint.android.mc10086.activity",
            manualCategory = null,
            systemCategory = AppCategory.SOCIAL,
            appLabel = "中国移动",
        )
        val unicomOnLabelAlone = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = null,
            systemCategory = AppCategory.SOCIAL,
            appLabel = "中国联通",
        )

        assertEquals(AppCategory.LIFESTYLE, mobile.category)
        assertEquals(AppCategory.LIFESTYLE, unicomOnLabelAlone.category)
        assertEquals(AppCategorySource.HEURISTIC, unicomOnLabelAlone.source)
    }

    @Test
    fun `a brand marker lends its strength to the head noun beside it`() {
        val result = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = null,
            systemCategory = AppCategory.SOCIAL,
            appLabel = "天翼生活",
        )

        assertEquals(AppCategory.LIFESTYLE, result.category)
    }

    @Test
    fun `brand marker still yields to a manual override`() {
        val result = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = AppCategory.TOOLS,
            systemCategory = AppCategory.SOCIAL,
            appLabel = "中国移动",
        )

        assertEquals(AppCategory.TOOLS, result.category)
        assertEquals(AppCategorySource.MANUAL, result.source)
    }

    @Test
    fun `opaque vendor packages resolve through the curated list`() {
        val expectations = mapOf(
            "com.smile.gifmaker" to AppCategory.MEDIA,
            "com.alibaba.android.rimet" to AppCategory.PRODUCTIVITY,
            "com.sinovatech.unicom.ui" to AppCategory.LIFESTYLE,
            "com.tencent.mtt" to AppCategory.TOOLS,
            "com.jd.jrapp" to AppCategory.FINANCE,
            "com.achievo.vipshop" to AppCategory.SHOPPING,
        )

        expectations.forEach { (packageName, expectedCategory) ->
            assertEquals(
                packageName,
                expectedCategory,
                classifier.classify(packageName, null, null).category,
            )
        }
    }

    @Test
    fun `app store label is not read as shopping`() {
        val result = classifier.classify(
            packageName = "com.example.unknown",
            manualCategory = null,
            systemCategory = null,
            appLabel = "华为应用商店",
        )

        assertEquals(AppCategory.TOOLS, result.category)
    }
}
