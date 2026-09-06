package me.excuse.app.overlay

import me.excuse.app.util.ReasonNormalizer

internal fun nextReasonUseNumber(
    reason: String,
    previousUseCounts: Map<String, Int>,
): Int? {
    val normalized = ReasonNormalizer.normalize(reason)
    val previousCount = previousUseCounts[normalized] ?: 0
    return if (normalized.isNotEmpty() && previousCount > 0) previousCount + 1 else null
}

internal fun canConfirmReason(submitting: Boolean, reason: String, minutes: Int): Boolean =
    !submitting && ReasonNormalizer.normalize(reason).isNotEmpty() && minutes > 0
