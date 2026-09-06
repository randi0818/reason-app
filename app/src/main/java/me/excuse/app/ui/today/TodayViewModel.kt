package me.excuse.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import me.excuse.app.data.UsageRepositoryContract
import me.excuse.app.data.db.UsageSession

/**
 * 一天的详情数据源。今天和历史日子用同一个 VM —— 查询是统一的。
 * 通过 viewModel(key = "day-$date", factory = Factory(repository, date)) 创建。
 */
class DayViewModel(
    repository: UsageRepositoryContract,
    val date: LocalDate,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val repo = repository
    private val sharingScope = scope ?: viewModelScope

    val sessions = repo.sessionsForDay(date).stateIn(
        sharingScope, SharingStarted.WhileSubscribed(5_000), emptyList<UsageSession>()
    )
    val entryCount = repo.entryCountForDay(date).stateIn(
        sharingScope, SharingStarted.WhileSubscribed(5_000), 0
    )
    val overrunCount = repo.overrunCountForDay(date).stateIn(
        sharingScope, SharingStarted.WhileSubscribed(5_000), 0
    )

    class Factory(
        private val repository: UsageRepositoryContract,
        private val date: LocalDate
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DayViewModel(repository, date) as T
    }
}
