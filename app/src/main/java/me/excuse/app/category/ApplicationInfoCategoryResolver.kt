package me.excuse.app.category

import android.content.pm.ApplicationInfo

/** Android-only boundary that translates [ApplicationInfo.category] for the pure classifier. */
interface ApplicationInfoCategoryResolver {
    fun resolve(applicationInfo: ApplicationInfo): AppCategory?
}

object AndroidApplicationInfoCategoryResolver : ApplicationInfoCategoryResolver {
    override fun resolve(applicationInfo: ApplicationInfo): AppCategory? =
        when (applicationInfo.category) {
            ApplicationInfo.CATEGORY_GAME -> AppCategory.GAME
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_IMAGE -> AppCategory.MEDIA

            ApplicationInfo.CATEGORY_NEWS -> AppCategory.READING

            ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
            ApplicationInfo.CATEGORY_MAPS -> AppCategory.LIFESTYLE
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> AppCategory.TOOLS

            else -> null
        }
}
