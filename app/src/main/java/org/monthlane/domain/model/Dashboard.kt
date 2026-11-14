package org.monthlane.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

data class CalendarEvent(
    val id: String,
    val calendarId: Long,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean = false,
    val description: String = "",
    val location: String = "",
    val color: Int? = null,
    val category: Int = 0,
    val providerId: Long? = null,
) {
    fun occursOn(date: LocalDate, zone: ZoneId): Boolean {
        if (allDay) {
            val first = start.atZone(ZoneOffset.UTC).toLocalDate()
            val exclusiveEnd = end.atZone(ZoneOffset.UTC).toLocalDate()
            return date >= first && date < exclusiveEnd
        }
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        return if (end <= start) start >= dayStart && start < dayEnd else start < dayEnd && end > dayStart
    }
    fun timeLabel(date: LocalDate, zone: ZoneId): String = when {
        allDay -> "All day"
        start < date.atStartOfDay(zone).toInstant() -> "Ongoing"
        else -> DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(zone).format(start)
    }
}
data class CalendarSource(val id: Long, val name: String, val accountName: String, val color: Int?, val visible: Boolean)
data class CalendarMarker(val calendarId: Long, val category: Int, val color: Int? = null)
enum class CalendarStatus(val message: String?) {
    SAMPLE(null), LOADING("Loading calendar…"), READY(null),
    PERMISSION_REQUIRED("Connect your calendar"), NO_CALENDARS("No calendars on this device"),
    NO_SELECTION("Choose calendars to display"), ERROR("Calendar unavailable · Tap to retry")
}
fun eventsOnDate(events: List<CalendarEvent>, date: LocalDate, zone: ZoneId): List<CalendarEvent> =
    events.filter { it.occursOn(date, zone) }.sortedWith(compareBy<CalendarEvent> { !it.allDay }.thenBy { it.start }.thenBy { it.id })
fun calendarMarkers(events: List<CalendarEvent>, month: YearMonth, zone: ZoneId): Map<LocalDate, List<CalendarMarker>> =
    (1..month.lengthOfMonth()).associate { day ->
        val date = month.atDay(day)
        date to eventsOnDate(events, date, zone).distinctBy { it.calendarId }.take(3).map { CalendarMarker(it.calendarId, it.category, it.color) }
    }
data class TaskLabel(val name: String, val color: Int? = null)
data class Task(val id: String, val title: String, val completed: Boolean = false, val writable: Boolean = true, val due: Long = 0, val modified: Long = 0, val labels: List<TaskLabel> = emptyList(), val completionUncertain: Boolean = false, val priority: String = "none")
fun taskPriorityColor(priority: String): Int? = when (priority) {
    "high" -> 0xFFFF8A7B.toInt()
    "medium" -> 0xFFD8A300.toInt()
    "low" -> 0xFF63B0FF.toInt()
    "none" -> 0xFFABABAB.toInt()
    else -> null
}
enum class TasksStatus { LOADING, READY, MISSING, PERMISSION, ERROR }
enum class WeatherStatus { LOADING, READY, MISSING_APP, PERMISSION_REQUIRED, INCOMPATIBLE, NO_LOCATIONS, CHOOSE_LOCATION, NO_DATA, ERROR }

data class Weather(
    val city: String, val temperature: Double, val minimum: Double?, val maximum: Double?, val condition: String,
    val code: String = "clear", val unit: String = "c", val updatedAt: Long? = null,
    val credits: List<String> = emptyList(), val creditLinks: Map<String, String> = emptyMap(), val sample: Boolean = false,
) {
    fun stale(now: Long = System.currentTimeMillis()): Boolean = updatedAt == null || now - updatedAt > 3 * 60 * 60 * 1000L || updatedAt > now + 5 * 60 * 1000L
}
data class CalendarDay(val date: LocalDate, val inMonth: Boolean)
data class DashboardState(
    val month: YearMonth,
    val selectedDate: LocalDate,
    val today: LocalDate,
    val weather: Weather?,
    val events: List<CalendarEvent>,
    val tasks: List<Task>,
    val indicators: Map<LocalDate, List<CalendarMarker>>,
    val calendarStatus: CalendarStatus = CalendarStatus.SAMPLE,
    val zone: ZoneId = ZoneId.systemDefault(),
    val weatherStatus: WeatherStatus = WeatherStatus.CHOOSE_LOCATION,
    val tasksStatus: TasksStatus = TasksStatus.LOADING,
    val tasksTotal: Int = 0,
)

fun monthGrid(month: YearMonth, firstDay: DayOfWeek = DayOfWeek.MONDAY): List<CalendarDay> {
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDay))
    return List(42) { offset ->
        val date = first.plusDays(offset.toLong())
        CalendarDay(date, YearMonth.from(date) == month)
    }
}

fun shiftSelection(date: LocalDate, months: Long): LocalDate = date.plusMonths(months)

enum class DashboardSize(val showLocation: Boolean) {
    COMPACT(false), REGULAR(false), TALL(true);
    companion object {
        fun forHeight(height: Float) = when {
            height < 470f -> COMPACT
            height < 570f -> REGULAR
            else -> TALL
        }
    }
}

fun compactHeader(height: Float, inset: Int, gap: Int, calendarHeight: Int, events: Boolean, tasks: Boolean, calendar: Boolean): Boolean {
    val sections = 1 + listOf(calendar, events, tasks).count { it }
    val needed = 2 * inset + (sections - 1) * gap + 69 +
        (if (calendar) calendarHeight else 0) + (if (events) 61 else 0) + (if (tasks) 55 else 0)
    return height < needed
}

fun normalizedWeatherStyle(style: String?): String = if (style == "compact") "compact" else "standard"

data class AgendaLimits(val events: Int, val tasks: Int)

fun agendaLimits(availableHeight: Int, eventCount: Int, taskCount: Int, eventRow: Int, taskRow: Int): AgendaLimits {
    var remaining = availableHeight.coerceAtLeast(0)
    var events = 0
    var tasks = 0
    // Share space while both lists have entries, then give the remainder to the other list.
    while (true) {
        var added = false
        if (events < eventCount && remaining >= eventRow) { events++; remaining -= eventRow; added = true }
        if (tasks < taskCount && remaining >= taskRow) { tasks++; remaining -= taskRow; added = true }
        if (!added) break
    }
    return AgendaLimits(events, tasks)
}

fun calendarExtraHeight(availableHeight: Int, limits: AgendaLimits, eventRow: Int, taskRow: Int): Int =
    (availableHeight - limits.events * eventRow - limits.tasks * taskRow).coerceIn(0, 60)
