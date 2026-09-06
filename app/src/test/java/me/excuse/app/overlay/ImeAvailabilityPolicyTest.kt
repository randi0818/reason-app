package me.excuse.app.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeAvailabilityPolicyTest {
    @Test
    fun focusedFieldWithoutAnyImeSignalReleasesOverlayActions() {
        assertTrue(
            shouldReleaseInputAfterImeOpenTimeout(
                requestStillCurrent = true,
                textFieldFocused = true,
                imeSeenSinceFocus = false,
                imeVisible = false,
            )
        )
    }

    @Test
    fun visibleOrPreviouslySeenImeKeepsNormalCloseGuards() {
        assertFalse(
            shouldReleaseInputAfterImeOpenTimeout(
                requestStillCurrent = true,
                textFieldFocused = true,
                imeSeenSinceFocus = false,
                imeVisible = true,
            )
        )
        assertFalse(
            shouldReleaseInputAfterImeOpenTimeout(
                requestStillCurrent = true,
                textFieldFocused = true,
                imeSeenSinceFocus = true,
                imeVisible = false,
            )
        )
    }

    @Test
    fun staleRequestCannotReleaseCurrentInputState() {
        assertFalse(
            shouldReleaseInputAfterImeOpenTimeout(
                requestStillCurrent = false,
                textFieldFocused = true,
                imeSeenSinceFocus = false,
                imeVisible = false,
            )
        )
    }
}
