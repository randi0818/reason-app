package me.excuse.app.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import me.excuse.app.R
import me.excuse.app.ui.monitored.MonitoredAppsScreen
import me.excuse.app.ui.settings.SettingsScreen
import me.excuse.app.ui.theme.Ink
import me.excuse.app.ui.theme.Muted
import me.excuse.app.ui.theme.Paper
import me.excuse.app.ui.today.TodayScreen

@Composable
fun AppNav(onRedoPermissions: () -> Unit) {
    // rememberSaveable：转屏 / 进程被回收后重建时停在原来的 tab，而不是跳回「数据」。
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Paper) {
                val items = listOf("数据", "名单", "设置")
                val icons = listOf(
                    R.drawable.ic_nav_bar_chart,
                    R.drawable.ic_nav_apps,
                    R.drawable.ic_nav_settings,
                )
                items.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                painter = painterResource(icons[i]),
                                contentDescription = label,
                            )
                        },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink,
                            selectedTextColor = Ink,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) { TodayScreen(onGoToMonitored = { tab = 1 }) }
            1 -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) { MonitoredAppsScreen() }
            else -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) { SettingsScreen(onRedoPermissions) }
        }
    }
}
