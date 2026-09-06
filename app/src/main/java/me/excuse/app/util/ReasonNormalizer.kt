package me.excuse.app.util

object ReasonNormalizer {
    // 拦截窗每次重组都会算一遍当前理由的 normalized 形式，所以这里走单遍 replace，
    // 不再为每个字符建 List<Char> 再 join。行为与旧实现完全一致。
    private val WHITESPACE_RUN = Regex("\\s+")

    fun normalize(raw: String): String =
        raw.replace('　', ' ').trim().lowercase().replace(WHITESPACE_RUN, " ")
}
