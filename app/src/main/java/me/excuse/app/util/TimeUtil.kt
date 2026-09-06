package me.excuse.app.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object TimeUtil {
    fun today(): LocalDate = LocalDate.now()

    fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun endOfDayMillis(date: LocalDate): Long =
        date.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun startOfTodayMillis(): Long = startOfDayMillis(today())

    fun toLocalDateTime(millis: Long): LocalDateTime =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    fun toLocalDate(millis: Long): LocalDate =
        toLocalDateTime(millis).toLocalDate()

    fun hhmm(millis: Long): String {
        val t = toLocalDateTime(millis)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /** "5/12" 风格的紧凑月日 */
    fun mdShort(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}"
}
