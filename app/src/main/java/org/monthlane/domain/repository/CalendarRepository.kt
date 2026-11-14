package org.monthlane.domain.repository

import org.monthlane.domain.model.CalendarEvent
import org.monthlane.domain.model.CalendarSource
import java.time.Instant

interface CalendarRepository {
    suspend fun getCalendars(): List<CalendarSource>
    suspend fun getEvents(from: Instant, to: Instant, calendarIds: Set<Long>): List<CalendarEvent>
}
