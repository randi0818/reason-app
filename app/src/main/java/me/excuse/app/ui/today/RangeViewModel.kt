package me.excuse.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import me.excuse.app.data.UsageRepositoryContract
import me.excuse.app.data.ReasonWallItem
import me.excuse.app.data.db.UsageSession
import me.excuse.app.util.TimeUtil
import java.time.LocalDate

/**
 * 滚动 N 天的数据源。周视图用 days=7、月视图用 days=30。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeViewModel(
    repository: UsageRepositoryContract,
    days: Int,
    scope: CoroutineScope? = null,
    initialDate: LocalDate = TimeUtil.today(),
) : ViewModel() {
    private val repo = repository
    private val sharingScope = scope ?: viewModelScope
    private val queryDate = MutableStateFlow(initialDate)

    val sessions = queryDate.flatMapLatest { repo.sessionsForRollingDays(days) }.stateIn(
        sharingScope, SharingStarted.WhileSubscribed(5_000), emptyList<UsageSession>()
    )
    val entryCount = queryDate.flatMapLatest { repo.entryCountRollingDays(days) }.stateIn(
        sharingScope, SharingStarted.WhileSubscribed(5_000), 0
    )
    val overrunCount = queryDate.flatMapLatest { repo.overrunCountRollingDays(days) }.stateIn(
        sharingScope, SharingStarted.WhileSubscribed(5_000), 0
    )
    val reasonWall = queryDate.flatMapLatest { repo.reasonWallForRollingDays(days) }.stateIn(
        sharingScope, SharingStarted.WhileSubscribed(5_000), emptyList<ReasonWallItem>()
    )

    /** Repository 在创建 Flow 时固化日期边界；跨午夜后用新日期重建全部滚动窗口查询。 */
    fun refreshForDate(date: LocalDate) {
        queryDate.value = date
    }

    class Factory(
        private val repository: UsageRepositoryContract,
        private val days: Int
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RangeViewModel(repository, days) as T
    }
}
