package org.monthlane.domain

import org.junit.Assert.*
import org.junit.Test
import org.monthlane.domain.model.*
import java.time.*

class CalendarEventsTest {
    private fun event(start: String, end: String, allDay: Boolean = false, calendar: Long = 1) =
        CalendarEvent("$calendar:$start", calendar, "Appointment", Instant.parse(start), Instant.parse(end), allDay)

    @Test fun `all day events use UTC dates and exclusive end regardless of device zone`() {
        val event = event("2026-11-12T00:00:00Z", "2026-11-14T00:00:00Z", true)
        listOf("America/Los_Angeles", "Europe/Rome", "Pacific/Kiritimati").forEach { name ->
            val zone = ZoneId.of(name)
            assertTrue(event.occursOn(LocalDate.of(2026, 11, 12), zone))
            assertTrue(event.occursOn(LocalDate.of(2026, 11, 13), zone))
            assertFalse(event.occursOn(LocalDate.of(2026, 11, 11), zone))
            assertFalse(event.occursOn(LocalDate.of(2026, 11, 14), zone))
        }
    }
    @Test fun `timed events span local midnight without appearing on exclusive end date`() {
        val zone = ZoneId.of("Europe/Rome")
        val event = event("2026-11-11T22:30:00Z", "2026-11-12T00:30:00Z")
        assertTrue(event.occursOn(LocalDate.of(2026, 11, 11), zone))
        assertTrue(event.occursOn(LocalDate.of(2026, 11, 12), zone))
        assertEquals("Ongoing", event.timeLabel(LocalDate.of(2026, 11, 12), zone))
        val midnightEnd = event("2026-11-11T22:30:00Z", "2026-11-11T23:00:00Z")
        assertFalse(midnightEnd.occursOn(LocalDate.of(2026, 11, 12), zone))
    }
    @Test fun `local day bounds handle spring and autumn daylight saving transitions`() {
        val zone = ZoneId.of("Europe/Rome")
        val spring = event("2026-03-29T21:30:00Z", "2026-03-29T22:30:00Z")
        assertTrue(spring.occursOn(LocalDate.of(2026, 3, 29), zone))
        assertTrue(spring.occursOn(LocalDate.of(2026, 3, 30), zone))
        val autumn = event("2026-10-25T22:30:00Z", "2026-10-25T23:30:00Z")
        assertTrue(autumn.occursOn(LocalDate.of(2026, 10, 25), zone))
        assertTrue(autumn.occursOn(LocalDate.of(2026, 10, 26), zone))
    }
    @Test fun `zero duration events appear only on their local start date`() {
        val event = event("2026-11-12T10:00:00Z", "2026-11-12T10:00:00Z")
        assertTrue(event.occursOn(LocalDate.of(2026, 11, 12), ZoneOffset.UTC))
        assertFalse(event.occursOn(LocalDate.of(2026, 11, 13), ZoneOffset.UTC))
    }
    @Test fun `agenda sorts all day first and markers represent distinct calendars capped at three`() {
        val date = LocalDate.of(2026, 11, 12)
        val timed = (1L..5L).map { id -> event("2026-11-12T09:00:00Z", "2026-11-12T10:00:00Z", calendar = id) }
        val allDay = event("2026-11-12T00:00:00Z", "2026-11-13T00:00:00Z", true)
        val events = timed + timed.first().copy(id = "another") + allDay
        assertTrue(eventsOnDate(events, date, ZoneOffset.UTC).first().allDay)
        assertEquals(3, calendarMarkers(events, YearMonth.from(date), ZoneOffset.UTC).getValue(date).size)
        assertEquals(0, calendarMarkers(events, YearMonth.from(date), ZoneOffset.UTC).getValue(date.plusDays(1)).size)
    }
}
