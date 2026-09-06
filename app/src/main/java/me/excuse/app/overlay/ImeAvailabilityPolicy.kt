package me.excuse.app.overlay

/** 只有当前这次 IME 请求从未成功显示时，才把焦点视为硬件输入。 */
internal fun shouldReleaseInputAfterImeOpenTimeout(
    requestStillCurrent: Boolean,
    textFieldFocused: Boolean,
    imeSeenSinceFocus: Boolean,
    imeVisible: Boolean,
): Boolean =
    requestStillCurrent && textFieldFocused && !imeSeenSinceFocus && !imeVisible
