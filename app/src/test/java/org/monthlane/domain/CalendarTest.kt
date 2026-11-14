package org.monthlane.domain

import org.junit.Assert.*
import org.junit.Test
import org.monthlane.domain.model.*
import org.monthlane.data.mock.MockDashboard
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CalendarTest {
    @Test fun `task priority colors match Tasks checkboxes`() {
        assertEquals(0xFFFF8A7B.toInt(), taskPriorityColor("high"))
        assertEquals(0xFFD8A300.toInt(), taskPriorityColor("medium"))
        assertEquals(0xFF63B0FF.toInt(), taskPriorityColor("low"))
        assertEquals(0xFFABABAB.toInt(), taskPriorityColor("none"))
        assertNull(taskPriorityColor("unknown"))
    }
    @Test fun `six week grid includes every date exactly once across year and leap boundaries`() {
        listOf(YearMonth.of(2026, 11), YearMonth.of(2024, 2), YearMonth.of(2027, 1)).forEach { month ->
            val grid = monthGrid(month)
            assertEquals(42, grid.size)
            assertEquals(DayOfWeek.MONDAY, grid.first().date.dayOfWeek)
            assertEquals((1..month.lengthOfMonth()).map { month.atDay(it) }, grid.filter { it.inMonth }.map { it.date })
            assertEquals(42, grid.map { it.date }.distinct().size)
            grid.zipWithNext().forEach { (first, second) -> assertEquals(first.date.plusDays(1), second.date) }
        }
    }
    @Test fun `month navigation clamps end of month and crosses years`() {
        assertEquals(LocalDate.of(2026, 2, 28), shiftSelection(LocalDate.of(2026, 1, 31), 1))
        assertEquals(LocalDate.of(2025, 12, 31), shiftSelection(LocalDate.of(2026, 1, 31), -1))
    }
    @Test fun `first weekday can be changed without losing month dates`() {
        val grid = monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), grid.first().date)
        assertEquals(30, grid.count { it.inMonth })
    }
    @Test fun `task completion is reversible and scoped to the selected date`() {
        val date = MockDashboard.referenceDate
        val initial = MockDashboard.load(date)
        val task = initial.tasks.first()
        val toggled = MockDashboard.load(date, completed = setOf(task.id))
        assertTrue(toggled.tasks.first().completed)
        assertFalse(MockDashboard.load(date).tasks.first().completed)
        assertFalse(MockDashboard.load(date.plusDays(1), completed = setOf(task.id)).tasks.first().completed)
        assertTrue(initial.tasks.last().completed)
        assertFalse(MockDashboard.load(date, completed = setOf(initial.tasks.last().id)).tasks.last().completed)
    }
    @Test fun `agenda shares available space and fills unused capacity`() {
        assertEquals(AgendaLimits(2, 2), agendaLimits(110, 8, 8, 27, 28))
        assertEquals(AgendaLimits(0, 4), agendaLimits(112, 0, 8, 27, 28))
        assertEquals(AgendaLimits(1, 3), agendaLimits(111, 1, 8, 27, 28))
        assertEquals(AgendaLimits(0, 0), agendaLimits(20, 2, 2, 27, 28))
    }
    @Test fun `calendar only expands into unused agenda space with a cap`() {
        assertEquals(0, calendarExtraHeight(110, AgendaLimits(2, 2), 27, 28))
        assertEquals(44, calendarExtraHeight(100, AgendaLimits(0, 2), 27, 28))
        assertEquals(60, calendarExtraHeight(300, AgendaLimits(0, 2), 27, 28))
        assertEquals(0, calendarExtraHeight(-10, AgendaLimits(0, 0), 27, 28))
    }
    @Test fun `header uses available room and selected sections`() {
        assertTrue(compactHeader(84f, 8, 5, 145, events = false, tasks = false, calendar = false))
        assertFalse(compactHeader(120f, 8, 5, 145, events = false, tasks = false, calendar = false))
        assertFalse(compactHeader(340f, 8, 5, 145, events = true, tasks = false, calendar = false))
        assertTrue(compactHeader(340f, 8, 5, 145, events = true, tasks = true, calendar = true))
        assertFalse(compactHeader(500f, 8, 5, 145, events = true, tasks = true, calendar = true))
    }
    @Test fun `retired weather style falls back to standard`() {
        assertEquals("standard", normalizedWeatherStyle("condition"))
        assertEquals("standard", normalizedWeatherStyle(null))
        assertEquals("compact", normalizedWeatherStyle("compact"))
    }
    @Test fun `small widgets remove secondary detail before reducing calendar coverage`() {
        assertEquals(DashboardSize.COMPACT, DashboardSize.forHeight(340f))
        assertFalse(DashboardSize.REGULAR.showLocation)
        assertTrue(DashboardSize.TALL.showLocation)
    }
}
