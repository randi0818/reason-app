package me.excuse.app.category

enum class AppCategory(val storageKey: String) {
    GAME("game"),
    SOCIAL("social"),
    MEDIA("media"),
    READING("reading"),
    SHOPPING("shopping"),
    PRODUCTIVITY("productivity"),
    LIFESTYLE("lifestyle"),
    FINANCE("finance"),
    TOOLS("tools"),
    OTHER("other");

    companion object {
        fun fromStorageKey(value: String): AppCategory? =
            entries.firstOrNull { it.storageKey == value }
    }
}

enum class AppCategorySource {
    MANUAL,
    SYSTEM,
    HEURISTIC,
    FALLBACK
}

data class AppClassification(
    val category: AppCategory,
    val source: AppCategorySource
)
