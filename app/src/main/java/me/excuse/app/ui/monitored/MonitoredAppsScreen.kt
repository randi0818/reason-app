package me.excuse.app.ui.monitored

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import me.excuse.app.R
import me.excuse.app.appServices
import me.excuse.app.category.AppCategory
import me.excuse.app.category.AppCategorySource
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Line
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.Paper
import me.excuse.app.ui.theme.SurfaceBg

@Composable
fun MonitoredAppsScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val vm: MonitoredAppsViewModel = viewModel(
        factory = MonitoredAppsViewModel.Factory(context, context.appServices.repository)
    )
    val classifiedApps by vm.classifiedApps.collectAsStateWithLifecycle()
    val monitored by vm.monitoredPackages.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()

    DisposableEffect(context, lifecycleOwner, vm) {
        var refreshedForCurrentResume = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!refreshedForCurrentResume) {
                    refreshedForCurrentResume = true
                    vm.refreshInstalledApps()
                }
                Lifecycle.Event.ON_PAUSE -> refreshedForCurrentResume = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // LifecycleRegistry 会把新 observer 同步到当前状态；此处只兜底非标准 owner，标志位避免重复。
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !refreshedForCurrentResume
        ) {
            refreshedForCurrentResume = true
            vm.refreshInstalledApps()
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                vm.refreshInstalledApps()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    // 搜索词和「显示系统 app」跟着转屏一起留下来；展开的分组集合不是可保存类型，
    // 折叠回默认状态影响很小，就不额外写 Saver 了。
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var expandedCategories by remember { mutableStateOf(emptySet<AppCategory>()) }

    val systemFiltered = remember(classifiedApps, showSystem) {
        classifiedApps.filter { showSystem || !it.app.isSystem }
    }
    val isSearching = query.isNotBlank()
    val searchResults = remember(systemFiltered, query) {
        if (!isSearching) {
            emptyList()
        } else {
            systemFiltered.filter {
                it.app.label.contains(query, ignoreCase = true) ||
                    it.app.packageName.contains(query, ignoreCase = true)
            }
        }
    }
    val grouped = remember(systemFiltered) {
        systemFiltered.groupBy { it.classification.category }
    }
    val hasNoResults = if (isSearching) searchResults.isEmpty() else systemFiltered.isEmpty()

    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索 app 名", color = Muted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceBg,
                        unfocusedContainerColor = SurfaceBg
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示系统 app", color = Ink)
                    Spacer(Modifier.width(8.dp))
                    SquareToggle(
                        checked = showSystem,
                        accessibilityLabel = "显示系统 app",
                        onCheckedChange = { showSystem = it }
                    )
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中…", color = Muted)
                }
            } else if (hasNoResults) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没找到 app", color = Muted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (isSearching) {
                        items(searchResults, key = { it.app.packageName }) { item ->
                            AppRow(
                                item = item,
                                checked = item.app.packageName in monitored,
                                onToggle = { vm.toggle(item.app, it) },
                                onCategorySelected = { vm.setCategory(item.app, it) }
                            )
                        }
                    } else {
                        AppCategory.entries.forEach { category ->
                            val categoryApps = grouped[category].orEmpty()
                            if (categoryApps.isNotEmpty()) {
                                val expanded = category in expandedCategories
                                item(key = "category-${category.storageKey}") {
                                    CategoryHeader(
                                        category = category,
                                        count = categoryApps.size,
                                        expanded = expanded,
                                        onClick = {
                                            expandedCategories = if (expanded) {
                                                expandedCategories - category
                                            } else {
                                                expandedCategories + category
                                            }
                                        }
                                    )
                                }
                                if (expanded) {
                                    items(categoryApps, key = { it.app.packageName }) { item ->
                                        AppRow(
                                            item = item,
                                            checked = item.app.packageName in monitored,
                                            onToggle = { vm.toggle(item.app, it) },
                                            onCategorySelected = { vm.setCategory(item.app, it) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    category: AppCategory,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val categoryName = stringResource(category.labelResource())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.app_category_group_header, categoryName, count),
            modifier = Modifier.weight(1f),
            color = Ink,
            style = MaterialTheme.typography.titleMedium
        )
        Text(if (expanded) "−" else "+", color = Muted)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AppRow(
    item: ClassifiedInstalledApp,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onCategorySelected: (AppCategory?) -> Unit
) {
    var menuExpanded by remember(item.app.packageName) { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (checked) "取消监控" else "加入监控",
                    onLongClickLabel = "调整分类",
                    onClick = { onToggle(!checked) },
                    onLongClick = { menuExpanded = true }
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = item.app.label
                    role = Role.Switch
                    toggleableState = ToggleableState(checked)
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.app.label, color = Ink, style = MaterialTheme.typography.titleMedium)
                Text(item.app.packageName, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            SquareToggleVisual(
                checked = checked,
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier
                .width(248.dp)
                .border(1.dp, Ink),
            shape = RoundedCornerShape(0.dp),
            containerColor = SurfaceBg,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ink)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_category_menu_title),
                    color = Paper,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.app_category_menu_subtitle),
                    color = Paper.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            CategoryMenuItem(
                label = stringResource(R.string.app_category_follow_system),
                selected = item.classification.source != AppCategorySource.MANUAL,
                onClick = {
                    menuExpanded = false
                    onCategorySelected(null)
                }
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .size(width = 224.dp, height = 1.dp)
                    .background(Line)
            )
            AppCategory.entries.forEach { category ->
                CategoryMenuItem(
                    label = stringResource(category.labelResource()),
                    selected = item.classification.source == AppCategorySource.MANUAL &&
                        item.classification.category == category,
                    onClick = {
                        menuExpanded = false
                        onCategorySelected(category)
                    }
                )
            }
        }
    }
}

/** AppRow 自己承担点击和开关语义；这里不能再生成第二个 TalkBack 节点。 */
@Composable
private fun SquareToggleVisual(checked: Boolean) {
    SquareToggleBody(checked = checked, modifier = Modifier)
}

@Composable
private fun CategoryMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (selected) Paper else Ink,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .border(1.dp, if (selected) Paper else Muted),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(Paper)
                    )
                }
            }
        },
        modifier = Modifier.background(if (selected) Ink else SurfaceBg),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp),
    )
}

private fun AppCategory.labelResource(): Int = when (this) {
    AppCategory.GAME -> R.string.app_category_game
    AppCategory.SOCIAL -> R.string.app_category_social
    AppCategory.MEDIA -> R.string.app_category_media
    AppCategory.READING -> R.string.app_category_reading
    AppCategory.SHOPPING -> R.string.app_category_shopping
    AppCategory.PRODUCTIVITY -> R.string.app_category_productivity
    AppCategory.LIFESTYLE -> R.string.app_category_lifestyle
    AppCategory.FINANCE -> R.string.app_category_finance
    AppCategory.TOOLS -> R.string.app_category_tools
    AppCategory.OTHER -> R.string.app_category_other
}

/**
 * Metro 风方形 toggle —— 替代 Material 3 Switch（圆角写死改不了）。
 * On: 黑底 + 白滑块在右
 * Off: 白底 + 灰滑块在左 + 灰边框
 */
@Composable
private fun SquareToggle(
    checked: Boolean,
    accessibilityLabel: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val trackWidth = 48.dp
    val trackHeight = 26.dp
    val thumbSize = 18.dp
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(trackWidth, trackHeight)
            .semantics { contentDescription = accessibilityLabel }
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null, // Metro 风：不要 ripple
                onValueChange = onCheckedChange,
            )
            .squareToggleAppearance(checked),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) { SquareToggleThumb(checked, thumbSize) }
}

@Composable
private fun SquareToggleBody(checked: Boolean, modifier: Modifier) {
    val trackWidth = 48.dp
    val trackHeight = 26.dp
    val thumbSize = 18.dp
    Box(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .squareToggleAppearance(checked),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) { SquareToggleThumb(checked, thumbSize) }
}

@Composable
private fun Modifier.squareToggleAppearance(checked: Boolean): Modifier =
    background(if (checked) Ink else SurfaceBg)
        .border(1.dp, if (checked) Ink else Muted)
        .padding(horizontal = 3.dp)

@Composable
private fun SquareToggleThumb(checked: Boolean, thumbSize: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(thumbSize)
            .background(if (checked) SurfaceBg else Muted)
    )
}
