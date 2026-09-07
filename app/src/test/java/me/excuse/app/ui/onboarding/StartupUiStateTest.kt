package me.excuse.app.ui.onboarding

import me.excuse.app.data.prefs.StartupPreferences
import me.excuse.app.data.prefs.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupUiStateTest {
    @Test
    fun `unread preferences never expose a page or a guessed theme`() {
        for (permissionsOk in listOf(false, true)) {
            for (redoPermissions in listOf(false, true)) {
                assertNull(resolveStartupUiState(null, permissionsOk, redoPermissions))
            }
        }
    }

    @Test
    fun `fresh install shows privacy even if permissions were granted externally`() {
        for (theme in ThemeMode.entries) {
            for (permissionsOk in listOf(false, true)) {
                assertEquals(
                    StartupUiState(theme, StartupPage.PRIVACY),
                    resolveStartupUiState(StartupPreferences(false, theme), permissionsOk, false),
                )
            }
        }
    }

    @Test
    fun `first loaded frame uses the saved dark theme and skips acknowledged privacy`() {
        assertNull(resolveStartupUiState(null, true, false))
        assertEquals(
            StartupUiState(ThemeMode.DARK, StartupPage.MAIN),
            resolveStartupUiState(StartupPreferences(true, ThemeMode.DARK), true, false),
        )
    }

    @Test
    fun `confirmation leads directly to permissions without resetting the theme`() {
        val before = StartupPreferences(false, ThemeMode.DARK)
        assertEquals(
            StartupUiState(ThemeMode.DARK, StartupPage.PRIVACY),
            resolveStartupUiState(before, false, false),
        )
        assertEquals(
            StartupUiState(ThemeMode.DARK, StartupPage.PERMISSIONS),
            resolveStartupUiState(before.copy(onboarded = true), false, false),
        )
    }

    @Test
    fun `revoking and restoring permissions never replays acknowledged privacy`() {
        for (theme in ThemeMode.entries) {
            val saved = StartupPreferences(true, theme)
            val pages = listOf(true, false, true).map { permissionsOk ->
                resolveStartupUiState(saved, permissionsOk, false)?.page
            }
            assertEquals(listOf(StartupPage.MAIN, StartupPage.PERMISSIONS, StartupPage.MAIN), pages)
        }
    }

    @Test
    fun `permission review stays open until the user returns`() {
        val saved = StartupPreferences(true, ThemeMode.SYSTEM)
        assertEquals(
            StartupUiState(ThemeMode.SYSTEM, StartupPage.PERMISSIONS),
            resolveStartupUiState(saved, true, true),
        )
        assertEquals(
            StartupUiState(ThemeMode.SYSTEM, StartupPage.MAIN),
            resolveStartupUiState(saved, true, false),
        )
    }

    @Test
    fun `theme changes keep the current destination`() {
        for (theme in ThemeMode.entries) {
            assertEquals(
                StartupUiState(theme, StartupPage.MAIN),
                resolveStartupUiState(StartupPreferences(true, theme), true, false),
            )
        }
    }

    @Test
    fun `granting the last permission keeps an unfinished setup open`() {
        val saved = StartupPreferences(true, ThemeMode.DARK)
        for (permissionsOk in listOf(false, true)) {
            assertEquals(
                StartupUiState(ThemeMode.DARK, StartupPage.PERMISSIONS),
                resolveStartupUiState(saved, permissionsOk, false, permissionSetupPending = true),
            )
        }
        assertEquals(
            StartupUiState(ThemeMode.DARK, StartupPage.MAIN),
            resolveStartupUiState(saved, true, false, permissionSetupPending = false),
        )
    }

    @Test
    fun `restored pending setup stays open even when all permissions are already granted`() {
        val saved = StartupPreferences(true, ThemeMode.LIGHT)
        assertEquals(
            StartupPage.PERMISSIONS,
            resolveStartupUiState(saved, true, false, permissionSetupPending = true)?.page,
        )
        assertNull(resolveStartupUiState(null, true, false, permissionSetupPending = true))
    }

    @Test
    fun `finishing setup cannot bypass a missing permission or the privacy introduction`() {
        assertEquals(
            StartupPage.PERMISSIONS,
            resolveStartupUiState(StartupPreferences(true, ThemeMode.LIGHT), false, false, false)?.page,
        )
        assertEquals(
            StartupPage.PRIVACY,
            resolveStartupUiState(StartupPreferences(false, ThemeMode.LIGHT), true, false, true)?.page,
        )
    }
}
