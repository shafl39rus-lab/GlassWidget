package dev.glasswidget

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

internal data class CalendarEvent(val date: LocalDate, val title: String)

internal fun monthEvents(context: Context, month: YearMonth): List<CalendarEvent>? {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    val zone = ZoneId.systemDefault()
    val first = month.atDay(1)
    val next = month.plusMonths(1).atDay(1)
    val builder = Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(builder, first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    ContentUris.appendId(builder, next.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    return runCatching {
        context.contentResolver.query(builder.build(),
            arrayOf(Instances.BEGIN, Instances.END, Instances.TITLE, Instances.ALL_DAY),
            "${Instances.VISIBLE} = 1", null, Instances.BEGIN)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val eventZone = if (cursor.getInt(3) == 1) ZoneOffset.UTC else zone
                    val startMillis = cursor.getLong(0)
                    val endMillis = maxOf(startMillis, cursor.getLong(1) - 1)
                    val start = Instant.ofEpochMilli(startMillis).atZone(eventZone).toLocalDate()
                    val end = Instant.ofEpochMilli(endMillis).atZone(eventZone).toLocalDate()
                    val title = cursor.getString(2).orEmpty().ifBlank { "Событие без названия" }
                    var date = maxOf(first, start)
                    while (date < next && date <= end) {
                        add(CalendarEvent(date, title))
                        date = date.plusDays(1)
                    }
                }
            }.sortedBy { it.date }
        }
    }.getOrNull()
}
