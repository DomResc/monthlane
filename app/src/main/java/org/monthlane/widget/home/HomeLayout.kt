package org.monthlane.widget.home

import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.*
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import org.monthlane.R
import org.monthlane.settings.CalendarEventActivity
import org.monthlane.settings.CalendarShortcutActivity
import android.content.Intent
import androidx.glance.appwidget.action.actionStartActivity
import org.monthlane.domain.model.*
import org.monthlane.settings.SettingsActivity
import org.monthlane.settings.WeatherActivity
import org.monthlane.settings.TasksShortcutActivity
import org.monthlane.settings.messageResource
import org.monthlane.theme.*
import java.time.format.DateTimeFormatter
import java.util.Locale

object WidgetComponents {
    const val CLOCK = "clock"
    const val WEATHER = "weather"
    const val CALENDAR = "calendar"
    const val EVENTS = "events"
    const val TASKS = "tasks"
    val all = setOf(CLOCK, WEATHER, CALENDAR, EVENTS, TASKS)
}

private fun dateLabel(pattern: String, locale: Locale) = DateTimeFormatter.ofPattern(pattern, locale)

@Composable
fun HomeLayout(state: DashboardState, appearance: WidgetAppearance, interactive: Boolean = true, fixedClock: Boolean = false, components: Set<String> = WidgetComponents.all, weatherStyle: String = "standard") {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val sample = state.calendarStatus == CalendarStatus.SAMPLE
    val size = DashboardSize.forHeight(LocalSize.current.height.value)
    val compact = size == DashboardSize.COMPACT
    val showLocation = size.showLocation && LocalSize.current.width.value >= 320f
    val colors = widgetColors(context, appearance)
    val tokens = appearance.preset
    val inset = when (size) { DashboardSize.COMPACT -> 8; DashboardSize.REGULAR -> tokens.padding - 4; DashboardSize.TALL -> tokens.padding - 2 }
    val gap = if (compact) 5 else tokens.gap
    val minimumCalendarHeight = when (size) { DashboardSize.COMPACT -> 145; DashboardSize.REGULAR -> 190; DashboardSize.TALL -> 215 }
    val hasHeader = WidgetComponents.CLOCK in components || WidgetComponents.WEATHER in components
    val headerCompact = compactHeader(LocalSize.current.height.value, inset, gap, minimumCalendarHeight,
        WidgetComponents.EVENTS in components, WidgetComponents.TASKS in components, WidgetComponents.CALENDAR in components)
    val headerHeight = if (!hasHeader) 0 else if (!headerCompact) 62 else if (WidgetComponents.WEATHER in components && normalizedWeatherStyle(weatherStyle) == "standard") 36 else 25
    val eventRowHeight = if (compact) 23 else 27
    val taskRowHeight = if (compact) 26 else 28
    val eventMessage = state.calendarStatus.message != null || state.events.isEmpty()
    val taskMessage = !sample && (state.tasksStatus != TasksStatus.READY || state.tasks.isEmpty())
    val eventBase = (if (compact) 21 + 10 else 27 + 18) + if (eventMessage) 30 else 0
    val taskBase = (if (compact) 25 + 6 else 28 + 12) + if (taskMessage) 24 else 0
    val sections = listOf(hasHeader, WidgetComponents.CALENDAR in components, WidgetComponents.EVENTS in components, WidgetComponents.TASKS in components).count { it }
    val available = LocalSize.current.height.value.toInt() - 2 * inset - (sections - 1).coerceAtLeast(0) * gap - headerHeight - (if (WidgetComponents.CALENDAR in components) minimumCalendarHeight else 0) - (if (WidgetComponents.EVENTS in components) eventBase else 0) - (if (WidgetComponents.TASKS in components) taskBase else 0)
    val limits = agendaLimits(available, if (eventMessage || WidgetComponents.EVENTS !in components) 0 else state.events.size,
        if (taskMessage || WidgetComponents.TASKS !in components) 0 else state.tasks.size, eventRowHeight, taskRowHeight)
    val calendarHeight = minimumCalendarHeight + calendarExtraHeight(available, limits, eventRowHeight, taskRowHeight)
    Column(GlanceModifier.fillMaxSize().background(colors.background).cornerRadius((tokens.radius + 6).dp).padding(inset.dp)) {
        if (hasHeader) Header(state, colors, headerCompact, headerHeight, interactive, fixedClock, if (headerCompact) 8 else 12, WidgetComponents.CLOCK in components, WidgetComponents.WEATHER in components, weatherStyle)
        if (WidgetComponents.CALENDAR in components) {
            if (hasHeader) Spacer(GlanceModifier.height(gap.dp))
            Calendar(state, colors, tokens, size, interactive, GlanceModifier.height(calendarHeight.dp))
        }
        if (WidgetComponents.EVENTS in components) {
        if (hasHeader || WidgetComponents.CALENDAR in components) Spacer(GlanceModifier.height(gap.dp))
        Column(GlanceModifier.fillMaxWidth().background(colors.surface).cornerRadius(tokens.radius.dp).padding(horizontal = (if (compact) 8 else 12).dp, vertical = (if (compact) 5 else 9).dp)) {
            Row(GlanceModifier.fillMaxWidth().height((if (compact) 21 else 27).dp), verticalAlignment = Alignment.CenterVertically) {
                Label(if (state.selectedDate == state.today) context.getString(R.string.today) else state.selectedDate.format(dateLabel("EEEE d MMMM", locale)), if (compact) 12 else 14, colors.text, FontWeight.Bold, GlanceModifier.defaultWeight())
                if (state.calendarStatus.message == null && state.events.size > limits.events) Label("+${state.events.size - limits.events}", 10, colors.muted)
                Box(GlanceModifier.width(36.dp).fillMaxHeight()
                    .semantics { contentDescription = context.getString(R.string.add_event) }
                    .then(if (interactive) GlanceModifier.clickable(actionStartActivity(CalendarShortcutActivity.intent(context, state.selectedDate, true))) else GlanceModifier), contentAlignment = Alignment.Center) {
                    Label("+", 22, colors.accent, FontWeight.Bold)
                }
            }
            if (state.calendarStatus.message != null) {
                Label(state.calendarStatus.localizedMessage(context).orEmpty(), 12, colors.muted, modifier = GlanceModifier.padding(vertical = 8.dp)
                    .then(if (interactive) GlanceModifier.clickable(actionStartActivity<SettingsActivity>()) else GlanceModifier))
            } else if (state.events.isEmpty()) {
                Label(context.getString(R.string.empty_day), 12, colors.muted, modifier = GlanceModifier.padding(vertical = 8.dp))
            } else state.events.take(limits.events).forEach { event ->
                val eventIntent = if (event.providerId != null) CalendarEventActivity.intent(context, event) else Intent(context, SettingsActivity::class.java)
                Row(GlanceModifier.fillMaxWidth().height((if (compact) 23 else 27).dp).then(if (interactive) GlanceModifier.clickable(actionStartActivity(eventIntent)) else GlanceModifier), verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.width(3.dp).height(15.dp).background(event.color?.let { Color(it) } ?: colors.markers[event.category.mod(3)]).cornerRadius(2.dp)) {}
                    Spacer(GlanceModifier.width(8.dp))
                    Label(when { event.allDay -> context.getString(R.string.all_day); event.start < state.selectedDate.atStartOfDay(state.zone).toInstant() -> context.getString(R.string.ongoing); else -> event.timeLabel(state.selectedDate, state.zone) }, 10, colors.muted, modifier = GlanceModifier.width((if (sample) 40 else if (locale.language == "it") 70 else 46).dp))
                    Label(event.title, 12, colors.text, modifier = GlanceModifier.defaultWeight())
                    if (showLocation && event.location.isNotBlank()) Label(event.location, 9, colors.muted, modifier = GlanceModifier.width(66.dp))
                }
            }
        }
        }
        if (WidgetComponents.TASKS in components) {
        if (hasHeader || WidgetComponents.CALENDAR in components || WidgetComponents.EVENTS in components) Spacer(GlanceModifier.height(gap.dp))
        Column(GlanceModifier.fillMaxWidth().defaultWeight().background(colors.secondarySurface).cornerRadius(tokens.radius.dp).padding(horizontal = (if (compact) 8 else 12).dp, vertical = (if (compact) 3 else 6).dp)) {
            Row(GlanceModifier.fillMaxWidth().height((if (compact) 25 else 28).dp), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.defaultWeight().fillMaxHeight()
                    .semantics { contentDescription = context.getString(R.string.tasks_open) }
                    .then(if (interactive) GlanceModifier.clickable(actionStartActivity(TasksShortcutActivity.intent(context, false))) else GlanceModifier),
                    contentAlignment = Alignment.CenterStart) {
                    Label(context.getString(if (sample) R.string.sample_tasks else R.string.tasks_title), 11, colors.muted, FontWeight.Medium)
                }
                val remainingTasks = (if (sample) state.tasks.size else state.tasksTotal) - state.tasks.size
                if (remainingTasks > 0) Label("+$remainingTasks", 10, colors.muted)
                Box(GlanceModifier.width(36.dp).fillMaxHeight()
                    .semantics { contentDescription = context.getString(R.string.tasks_add) }
                    .then(if (interactive) GlanceModifier.clickable(actionStartActivity(TasksShortcutActivity.intent(context, true))) else GlanceModifier),
                    contentAlignment = Alignment.Center) {
                    Label("+", 22, colors.accent, FontWeight.Bold)
                }
            }
            if (!sample && state.tasksStatus != TasksStatus.READY) {
                val message = when (state.tasksStatus) {
                    TasksStatus.LOADING -> R.string.tasks_loading
                    TasksStatus.MISSING, TasksStatus.PERMISSION -> R.string.widget_connect_provider
                    else -> R.string.tasks_error
                }
                Label(context.getString(message), 12, colors.muted, modifier = GlanceModifier.padding(vertical = 5.dp).then(if (interactive) GlanceModifier.clickable(actionStartActivity<SettingsActivity>()) else GlanceModifier))
            } else if (!sample && state.tasks.isEmpty()) Label(context.getString(R.string.tasks_empty), 12, colors.muted, modifier = GlanceModifier.padding(vertical = 5.dp))
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
            items(state.tasks, itemId = { it.id.hashCode().toLong() }) { task ->
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    CheckBox(
                        checked = task.completed,
                        onCheckedChange = if (interactive && (sample || task.writable)) actionRunCallback<ToggleTaskAction>(actionParametersOf(HomeWidgetState.taskParameter to task.id, HomeWidgetState.taskDueParameter to task.due, HomeWidgetState.taskModifiedParameter to task.modified)) else null,
                        modifier = GlanceModifier.size(36.dp).semantics { contentDescription = context.getString(if (task.completed) R.string.completed_task else R.string.pending_task, if (sample) taskTitle(context, task.title) else task.title) },
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = if (sample) colors.faint else taskPriorityColor(task.priority)?.let { Color(it) } ?: colors.faint),
                    )
                    Column(GlanceModifier.defaultWeight().then(if (interactive && !sample && task.id.toLongOrNull() != null) GlanceModifier.clickable(actionStartActivity(TasksShortcutActivity.taskIntent(context, task.id))) else GlanceModifier)) {
                        Text(if (sample) taskTitle(context, task.title) else task.title,
                            style = TextStyle(color = ColorProvider(if (task.completed) colors.muted else colors.text), fontSize = 12.sp), maxLines = 3)
                        if (task.completionUncertain) Label(context.getString(R.string.tasks_check_completion), 10, colors.accent)
                        else if (task.completed && !sample && task.writable) Label(context.getString(R.string.tasks_undo), 10, colors.accent)
                        else if (task.labels.isNotEmpty()) Label(task.labels.joinToString(" · ") { it.name }, 10, colors.accent)
                    }
                }
            }
            }
        }
        }
    }
}

@Composable
private fun Header(state: DashboardState, colors: WidgetColors, compact: Boolean, height: Int, interactive: Boolean, fixedClock: Boolean, horizontalInset: Int, showClock: Boolean, showWeather: Boolean, weatherStyle: String) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    Row(GlanceModifier.fillMaxWidth().height(height.dp).padding(horizontal = horizontalInset.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showClock) Column(GlanceModifier.defaultWeight()) {
            run {
                val clock = RemoteViews(context.packageName, R.layout.widget_clock).apply {
                    setTextColor(R.id.widget_clock, colors.text.toArgb())
                    setTextViewTextSize(R.id.widget_clock, TypedValue.COMPLEX_UNIT_SP, if (compact) 20f else 34f)
                    if (fixedClock) {
                        setCharSequence(R.id.widget_clock, "setFormat12Hour", "'09:41'")
                        setCharSequence(R.id.widget_clock, "setFormat24Hour", "'09:41'")
                    }
                }
                AndroidRemoteViews(clock, GlanceModifier.fillMaxWidth().height((if (compact) 25 else 40).dp))
            }
            if (!compact) Label(state.today.format(dateLabel("EEEE, d MMM", locale)), 11, colors.muted)
        }
        val weather = state.weather
        val weatherDescription = weather?.let {
            val condition = if (weatherCondition(it.code) != R.string.weather_unknown) context.getString(weatherCondition(it.code)) else it.condition
            listOf(it.city, temperatureLabel(it.temperature, it.unit), condition,
                if (it.stale() && !it.sample) context.getString(R.string.weather_stale) else "").filter { part -> part.isNotBlank() }.joinToString(", ")
        }
        if (showWeather) Row(GlanceModifier.defaultWeight()
            .then(if (weatherDescription != null) GlanceModifier.semantics { contentDescription = weatherDescription } else GlanceModifier)
            .then(if (interactive) GlanceModifier.clickable(if (weather != null) actionStartActivity<WeatherActivity>() else actionStartActivity<SettingsActivity>()) else GlanceModifier), horizontalAlignment = Alignment.End, verticalAlignment = Alignment.CenterVertically) {
            if (weather == null) {
                Column(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    if (!compact) Label(context.getString(R.string.component_weather), 12, colors.text, FontWeight.Medium)
                    val message = when (state.weatherStatus) {
                        WeatherStatus.MISSING_APP, WeatherStatus.PERMISSION_REQUIRED -> R.string.widget_connect_provider
                        else -> state.weatherStatus.messageResource()
                    }
                    Text(context.getString(message),
                        style = TextStyle(color = ColorProvider(colors.muted), fontSize = (if (compact) 10 else 11).sp), maxLines = 2)
                }
            } else {
                Spacer(GlanceModifier.width(6.dp))
                Image(ImageProvider(weatherIcon(weather.code)), weather.condition.ifBlank { context.getString(R.string.weather_unknown) }, GlanceModifier.size((if (compact) 22 else 30).dp))
                if (weather.stale() && !weather.sample) Label("!", 10, colors.muted)
                Spacer(GlanceModifier.width(5.dp))
                Column(GlanceModifier.width((if (compact) 46 else 58).dp), horizontalAlignment = Alignment.End) {
                    Label(temperatureLabel(weather.temperature, weather.unit), if (compact) 20 else 27, colors.text)
                    if (normalizedWeatherStyle(weatherStyle) == "standard") Label("${temperatureLabel(weather.minimum, weather.unit)} / ${temperatureLabel(weather.maximum, weather.unit)}", 9, colors.muted)
                }
            }
        }
    }
}

@Composable
private fun Calendar(state: DashboardState, colors: WidgetColors, preset: WidgetPreset, size: DashboardSize, interactive: Boolean, modifier: GlanceModifier) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val firstDay = java.time.temporal.WeekFields.of(locale).firstDayOfWeek
    val compact = size == DashboardSize.COMPACT
    Column(modifier.fillMaxWidth().background(if (preset == WidgetPreset.MATERIAL) colors.surface else colors.background).cornerRadius(preset.radius.dp).padding(horizontal = (if (compact) 8 else 12).dp, vertical = (if (compact) 2 else 5).dp)) {
        Row(GlanceModifier.fillMaxWidth().height((if (compact) 25 else 32).dp), verticalAlignment = Alignment.CenterVertically) {
            Label(state.month.format(dateLabel("MMMM yyyy", locale)).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }, if (compact) 13 else 15, colors.text, FontWeight.Bold,
                GlanceModifier.defaultWeight().then(if (interactive) GlanceModifier.clickable(actionStartActivity(CalendarShortcutActivity.intent(context, state.selectedDate, false))) else GlanceModifier))
            Box(GlanceModifier.width((if (compact) 35 else 42).dp).fillMaxHeight()
                .semantics { contentDescription = context.getString(R.string.today) }
                .then(if (interactive) GlanceModifier.clickable(actionRunCallback<MoveMonthAction>(actionParametersOf(HomeWidgetState.moveParameter to 0))) else GlanceModifier), contentAlignment = Alignment.Center) {
                Label(context.getString(R.string.today), if (compact) 10 else 11, colors.accent, FontWeight.Bold)
            }
            MonthButton("‹", context.getString(R.string.previous_month), -1, colors, interactive, compact)
            MonthButton("›", context.getString(R.string.next_month), 1, colors, interactive, compact)
        }
        if (state.calendarStatus != CalendarStatus.SAMPLE && state.calendarStatus != CalendarStatus.READY && state.calendarStatus != CalendarStatus.LOADING) {
            Label(state.calendarStatus.localizedMessage(context).orEmpty(), 10, colors.muted,
                modifier = GlanceModifier.fillMaxWidth().height(18.dp).then(if (interactive) GlanceModifier.clickable(actionStartActivity<SettingsActivity>()) else GlanceModifier))
        }
        Row(GlanceModifier.fillMaxWidth().height((if (compact) 14 else 20).dp)) {
            (0..6).map { firstDay.plus(it.toLong()).getDisplayName(java.time.format.TextStyle.NARROW, locale) }.forEach { weekday ->
                Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) { Label(weekday, 9, colors.muted) }
            }
        }
        monthGrid(state.month, firstDay).chunked(7).forEach { week ->
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                week.forEach { day ->
                    val selected = day.date == state.selectedDate
                    val today = day.date == state.today
                    val circle = when (size) { DashboardSize.COMPACT -> 13; DashboardSize.REGULAR -> 17; DashboardSize.TALL -> 23 }
                    val label = "${day.date.format(dateLabel("EEEE d MMMM yyyy", locale))}${if (today) context.getString(R.string.today_suffix) else ""}${if (selected) context.getString(R.string.selected_suffix) else ""}"
                    Column(GlanceModifier.defaultWeight().fillMaxHeight().semantics { contentDescription = label }
                        .then(if (interactive) GlanceModifier.clickable(actionRunCallback<SelectDayAction>(actionParametersOf(HomeWidgetState.dateParameter to day.date.toString()))) else GlanceModifier), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                        val background = if (preset == WidgetPreset.MATERIAL) colors.surface else colors.background
                        val highlightSize = circle + if (selected) 4 else 0
                        Box(GlanceModifier.size(highlightSize.dp).background(when { today && selected -> colors.onAccent; today || selected -> colors.accent; else -> background }).cornerRadius((highlightSize / 2f).dp), contentAlignment = Alignment.Center) {
                            Column(GlanceModifier.size((highlightSize - 2).dp).background(if (selected) colors.accent else background).cornerRadius(((highlightSize - 2) / 2f).dp), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                                Label(day.date.dayOfMonth.toString(), when (size) { DashboardSize.COMPACT -> 9; DashboardSize.REGULAR -> 10; DashboardSize.TALL -> 11 },
                                    when { selected -> colors.onAccent; !day.inMonth -> colors.faint; today -> colors.accent; else -> colors.text },
                                    if (selected || today) FontWeight.Bold else FontWeight.Normal)
                                if (selected) EventMarkers(state.indicators[day.date].orEmpty(), colors, selected = true)
                            }
                        }
                        // The selected circle consumes the marker row, keeping total day height unchanged.
                        if (!selected) Row(GlanceModifier.height(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (day.inMonth) EventMarkers(state.indicators[day.date].orEmpty(), colors)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthButton(text: String, description: String, movement: Int, colors: WidgetColors, interactive: Boolean, compact: Boolean) {
    Box(GlanceModifier.width((if (compact) 30 else 36).dp).fillMaxHeight().semantics { contentDescription = description }
        .then(if (interactive) GlanceModifier.clickable(actionRunCallback<MoveMonthAction>(actionParametersOf(HomeWidgetState.moveParameter to movement))) else GlanceModifier), contentAlignment = Alignment.Center) {
        Label(text, 22, colors.text)
    }
}

@Composable
private fun Label(text: String, size: Int, color: Color, weight: FontWeight = FontWeight.Normal, modifier: GlanceModifier = GlanceModifier) {
    Text(text, modifier, style = TextStyle(color = ColorProvider(color), fontSize = size.sp, fontWeight = weight), maxLines = 1)
}

private fun taskTitle(context: android.content.Context, title: String): String = when (title) {
    "Send the proposal" -> context.getString(R.string.task_proposal)
    "Update the server" -> context.getString(R.string.task_server)
    "Check the backup" -> context.getString(R.string.task_backup)
    else -> title
}

@Composable
private fun EventMarkers(markers: List<CalendarMarker>, colors: WidgetColors, selected: Boolean = false) {
    Row(GlanceModifier.height(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        markers.take(3).forEach { marker ->
            val color = marker.color?.let { Color(it) } ?: colors.markers[marker.category.mod(3)]
            Box(GlanceModifier.padding(horizontal = 1.dp).size((if (selected) 4 else 3).dp).background(if (selected) colors.onAccent else color).cornerRadius(2.dp), contentAlignment = Alignment.Center) {
                if (selected) Box(GlanceModifier.size(2.dp).background(color).cornerRadius(1.dp)) {}
            }
        }
    }
}

private fun temperatureLabel(value: Double?, unit: String): String {
    if (value == null) return "—"
    val suffix = when (unit) { "f" -> "°F"; "k" -> " K"; else -> "°" }
    return "${kotlin.math.round(value).toInt()}$suffix"
}
private fun weatherIcon(code: String): Int = when (code) {
    "clear" -> R.drawable.ic_sun
    "rain", "sleet", "hail" -> R.drawable.ic_weather_rain
    "snow" -> R.drawable.ic_weather_snow
    "thunder", "thunderstorm" -> R.drawable.ic_weather_storm
    "fog", "haze", "wind" -> R.drawable.ic_weather_fog
    "cloudy", "partly_cloudy" -> R.drawable.ic_weather_cloud
    else -> R.drawable.ic_weather_unknown
}
private fun weatherCondition(code: String): Int = when (code) {
    "clear" -> R.string.weather_clear
    "partly_cloudy" -> R.string.weather_partly_cloudy
    "cloudy" -> R.string.weather_cloudy
    "rain" -> R.string.weather_rain
    "snow" -> R.string.weather_snow
    "wind" -> R.string.weather_wind
    "fog" -> R.string.weather_fog
    "haze" -> R.string.weather_haze
    "sleet" -> R.string.weather_sleet
    "hail" -> R.string.weather_hail
    "thunder" -> R.string.weather_thunder
    "thunderstorm" -> R.string.weather_thunderstorm
    else -> R.string.weather_unknown
}
