package me.excuse.app.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 周视图和月视图的数据块共用同一套几何。
 *
 * 月视图的 5×6 方格是正方形，所以整块的高度完全由屏幕宽度算出来；周视图的 timeline
 * 原本写死 260dp。两者在同一台机器上高度对不上，而且差多少还随屏幕宽度变 —— 横滑
 * 切 tab 时数据块会跳一下。
 *
 * 这里把月视图的几何抽成单一来源，周视图直接引用同一个高度。以后改列数、行数或格间距，
 * 两个 tab 会一起变，不会再各漂各的。
 */
object RangeGeometry {
    /** 两个视图的数据块共用的左右外边距。 */
    val HorizontalPadding = 16.dp

    /** 月视图方格之间的间隙。 */
    val CellGap = 2.dp

    const val COLUMNS = 6
    const val ROWS = 5

    /**
     * 月视图 5×6 方格块的总高度，也是周视图 timeline 应该取的高度。
     *
     * 方格宽度 = (可用宽度 - 5 道间隙) / 6，正方形所以高度相同；再乘 5 行加 4 道行间隙。
     * 用 `screenWidthDp` 而不是 `BoxWithConstraints`，是因为两个视图都是整屏宽的
     * `fillMaxWidth`，直接用屏幕宽度可以避免为了一个高度值多套一层测量容器。
     */
    @Composable
    fun blockHeight(): Dp {
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val contentWidth = screenWidth - HorizontalPadding * 2
        val cell = (contentWidth - CellGap * (COLUMNS - 1)) / COLUMNS
        return cell * ROWS + CellGap * (ROWS - 1)
    }
}
