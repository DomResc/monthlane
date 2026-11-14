package org.monthlane.data.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monthlane.domain.model.CalendarEvent
import org.monthlane.domain.model.CalendarSource
import org.monthlane.domain.repository.CalendarRepository
import java.time.Instant

class AndroidCalendarRepository(private val resolver: ContentResolver, private val unnamedCalendar: String = "Unnamed calendar", private val untitledEvent: String = "Untitled event") : CalendarRepository {
    override suspend fun getCalendars(): List<CalendarSource> = withContext(Dispatchers.IO) {
        val fields = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.VISIBLE)
        val cursor = resolver.query(CalendarContract.Calendars.CONTENT_URI, fields, null, null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE") ?: error("Calendar provider returned no cursor")
        cursor.use {
            buildList {
                while (it.moveToNext()) add(CalendarSource(it.getLong(0), it.getString(1).orEmpty().ifBlank { unnamedCalendar },
                    it.getString(2).orEmpty(), color(it.getInt(3)), it.getInt(4) != 0))
            }
        }
    }

    override suspend fun getEvents(from: Instant, to: Instant, calendarIds: Set<Long>): List<CalendarEvent> = withContext(Dispatchers.IO) {
        require(from < to)
        if (calendarIds.isEmpty()) return@withContext emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, from.toEpochMilli())
            ContentUris.appendId(this, to.toEpochMilli())
        }.build()
        val fields = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DISPLAY_COLOR)
        val ids = calendarIds.sorted()
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }}) AND " +
            "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ?) AND ${CalendarContract.Events.DELETED} = 0"
        val args = ids.map(Long::toString).plus(CalendarContract.Events.STATUS_CANCELED.toString()).toTypedArray()
        val cursor = resolver.query(uri, fields, selection, args, "${CalendarContract.Instances.BEGIN} ASC")
            ?: error("Calendar provider returned no cursor")
        cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val eventId = it.getLong(0)
                    val begin = it.getLong(3)
                    val end = it.getLong(4)
                    val calendarId = it.getLong(1)
                    add(CalendarEvent("$eventId:$begin", calendarId, it.getString(2).orEmpty().ifBlank { untitledEvent },
                        Instant.ofEpochMilli(begin), Instant.ofEpochMilli(end), it.getInt(5) != 0,
                        it.getString(6).orEmpty(), it.getString(7).orEmpty(), color(it.getInt(8)),
                        ids.indexOf(calendarId).mod(3), eventId))
                }
            }
        }
    }

    private fun color(value: Int): Int? = value.takeIf { it != 0 }?.or(0xFF000000.toInt())
}
