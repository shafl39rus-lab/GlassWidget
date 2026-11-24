package dev.glasswidget

internal fun dailySteps(sameDay: Boolean, sameBoot: Boolean, total: Long, previous: Long, current: Long): Long {
    if (!sameDay) return 0
    val delta = if (sameBoot && previous >= 0 && current >= previous) current - previous else 0
    return total + delta
}
