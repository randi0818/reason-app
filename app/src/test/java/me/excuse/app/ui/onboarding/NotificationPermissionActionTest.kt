package me.excuse.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionActionTest {
    @Test
    fun firstRequestAndRationaleCanUseRuntimeDialog() {
        assertEquals(
            NotificationPermissionAction.REQUEST,
            notificationPermissionAction(requestAttempted = false, shouldShowRationale = false),
        )
        assertEquals(
            NotificationPermissionAction.REQUEST,
            notificationPermissionAction(requestAttempted = true, shouldShowRationale = true),
        )
    }

    @Test
    fun deniedWithoutRationaleFallsBackToSystemSettings() {
        assertEquals(
            NotificationPermissionAction.OPEN_SETTINGS,
            notificationPermissionAction(requestAttempted = true, shouldShowRationale = false),
        )
    }
}
