package me.excuse.app.ui.onboarding

import me.excuse.app.data.prefs.StartupPreferences
import me.excuse.app.data.prefs.ThemeMode

internal enum class StartupPage { PRIVACY, PERMISSIONS, MAIN }

internal data class StartupUiState(
    val themeMode: ThemeMode,
    val page: StartupPage,
)

internal fun resolveStartupUiState(
    preferences: StartupPreferences?,
    permissionsOk: Boolean,
    redoPermissions: Boolean,
    permissionSetupPending: Boolean = false,
): StartupUiState? {
    // 未加载不能当成未确认，也不能先用默认主题绘制任何页面。
    if (preferences == null) return null
    return StartupUiState(
        themeMode = preferences.themeMode,
        page = when {
            !preferences.onboarded -> StartupPage.PRIVACY
            permissionSetupPending || redoPermissions || !permissionsOk -> StartupPage.PERMISSIONS
            else -> StartupPage.MAIN
        },
    )
}
