package org.monthlane.data.mock

import org.monthlane.domain.model.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

object MockDashboard {
    val referenceDate: LocalDate = LocalDate.of(2026, 11, 12)
    fun indicators(date: LocalDate): Int = if (date.dayOfMonth == 12) 3 else when (date.dayOfMonth % 7) { 0 -> 0; 1, 4 -> 2; 2 -> 3; else -> 1 }

    fun load(selectedDate: LocalDate, month: YearMonth = YearMonth.from(selectedDate),
             today: LocalDate = LocalDate.now(), completed: Set<String> = emptySet()): DashboardState {
        val prefix = selectedDate.toString()
        val zone = ZoneId.systemDefault()
        fun event(id: String, title: String, time: LocalTime, location: String, category: Int): CalendarEvent {
            val start = selectedDate.atTime(time).atZone(zone).toInstant()
            return CalendarEvent(id, category.toLong(), title, start, start.plusSeconds(3600), location = location, category = category)
        }
        val events = if (indicators(selectedDate) == 0) emptyList() else listOf(
            event("$prefix-review", "Design review", LocalTime.of(9, 30), "Meeting room", 0),
            event("$prefix-call", "Project Alpha call", LocalTime.of(14, 0), "Online", 1),
            event("$prefix-dentist", "Dentist", LocalTime.of(17, 30), "Studio Rossi", 2),
        ).take(indicators(selectedDate))
        val tasks = listOf(
            Task("$prefix-proposal", "Send the proposal"),
            Task("$prefix-server", "Update the server"),
            Task("$prefix-backup", "Check the backup", true),
        ).map { task -> if (task.id in completed) task.copy(completed = !task.completed) else task }
        return DashboardState(month, selectedDate, today, Weather("Milan", 12.0, 7.0, 16.0, "Sunny", sample = true), events, tasks,
            (1..month.lengthOfMonth()).associate { day -> month.atDay(day) to List(indicators(month.atDay(day))) { CalendarMarker(it.toLong(), it) } })
    }
}
