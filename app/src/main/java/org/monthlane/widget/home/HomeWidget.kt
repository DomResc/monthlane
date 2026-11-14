package org.monthlane.widget.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.*
import androidx.glance.GlanceId
import androidx.glance.currentState
import androidx.glance.appwidget.*
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.action.ActionParameters
import androidx.glance.state.PreferencesGlanceStateDefinition
import org.monthlane.data.calendar.*
import org.monthlane.data.cache.DashboardCache
import org.monthlane.domain.model.CalendarStatus
import org.monthlane.domain.model.TasksStatus
import org.monthlane.data.weather.BreezyWeatherRepository
import org.monthlane.sync.CalendarRefreshWorker
import org.monthlane.data.mock.MockDashboard
import org.monthlane.data.tasks.*
import org.monthlane.domain.model.shiftSelection
import org.monthlane.theme.*
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.YearMonth

object HomeWidgetState {
    val revision = longPreferencesKey("calendar_revision")
    val taskRevision = longPreferencesKey("task_revision")
    val taskDisplayRevision = longPreferencesKey("task_display_revision")
    val sample = booleanPreferencesKey("sample_calendar")
    val selected = stringPreferencesKey("selected_date")
    val selectionDay = stringPreferencesKey("selection_day")
    val weatherStyle = stringPreferencesKey("weather_style")
    val completed = stringSetPreferencesKey("completed_tasks")
    val dateParameter = ActionParameters.Key<String>("date")
    val moveParameter = ActionParameters.Key<Int>("move")
    val preset = stringPreferencesKey("preset")
    val mode = stringPreferencesKey("theme_mode")
    val palette = stringPreferencesKey("palette")
    val components = stringSetPreferencesKey("components")
    val taskParameter = ActionParameters.Key<String>("task")
    val taskDueParameter = ActionParameters.Key<Long>("task_due")
    val taskModifiedParameter = ActionParameters.Key<Long>("task_modified")
    fun selectedDate(prefs: Preferences): LocalDate {
        val selected = runCatching { LocalDate.parse(prefs[selected]) }.getOrNull() ?: return LocalDate.now()
        val selectionDay = runCatching { LocalDate.parse(prefs[selectionDay]) }.getOrNull()
        return if (selectionDay != null && selected == selectionDay) LocalDate.now() else selected
    }
}

class HomeWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        org.monthlane.sync.PendingTasks.recover(context)
        val defaults = AppearanceStore.load(context)
        val initialPrefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val initialMonth = YearMonth.from(HomeWidgetState.selectedDate(initialPrefs))
        val memorySnapshot = CalendarData.cachedMonth(context, initialMonth)
        val savedSnapshot = if (memorySnapshot == null) DashboardCache.calendar(context, initialMonth) else null
        val initialSnapshot = if (initialPrefs[HomeWidgetState.sample] == true) CalendarSnapshot(CalendarStatus.SAMPLE)
            else memorySnapshot ?: savedSnapshot
                ?: CalendarSnapshot(if (CalendarSettings.canRead(context)) CalendarStatus.LOADING else CalendarStatus.PERMISSION_REQUIRED)
        val initialWeather = if (initialPrefs[HomeWidgetState.sample] == true) null else DashboardCache.weather(context)
            ?: org.monthlane.data.weather.WeatherSnapshot(when {
                context.packageManager.resolveContentProvider(BreezyWeatherRepository.AUTHORITY, 0) == null -> org.monthlane.domain.model.WeatherStatus.MISSING_APP
                !BreezyWeatherRepository.canRead(context) -> org.monthlane.domain.model.WeatherStatus.PERMISSION_REQUIRED
                else -> org.monthlane.domain.model.WeatherStatus.LOADING
            })
        val initialTasks = if (initialPrefs[HomeWidgetState.sample] == true) null else DashboardCache.tasks(context)
            ?: TasksSnapshot(when {
                !TasksRepository.available(context) -> TasksStatus.MISSING
                !TasksRepository.canRead(context) -> TasksStatus.PERMISSION
                else -> TasksStatus.LOADING
            })
        provideContent {
            val prefs = currentState<Preferences>()
            val appearance = WidgetAppearance(
                WidgetPreset.entries.firstOrNull { it.name == prefs[HomeWidgetState.preset] } ?: defaults.preset,
                ThemeMode.entries.firstOrNull { it.name == prefs[HomeWidgetState.mode] } ?: defaults.mode,
                Palette.entries.firstOrNull { it.name == prefs[HomeWidgetState.palette] } ?: defaults.palette,
            )
            val selected = HomeWidgetState.selectedDate(prefs)
            val sample = prefs[HomeWidgetState.sample] ?: false
            val revision = prefs[HomeWidgetState.revision] ?: 0L
            val taskRevision = prefs[HomeWidgetState.taskRevision] ?: 0L
            val month = YearMonth.from(selected)
            var loadedMonth by remember { mutableStateOf(month) }
            val snapshot by produceState(if (month == initialMonth) initialSnapshot else CalendarSnapshot(CalendarStatus.LOADING), month, revision, sample) {
                if (loadedMonth != month) {
                    val cachedMonth = if (sample) null else DashboardCache.calendar(context, month)
                    value = cachedMonth ?: CalendarSnapshot(CalendarStatus.LOADING)
                    loadedMonth = month
                }
                if (!sample) CalendarData.observeMonth(context, month).collect {
                    val cached = if (it.status == CalendarStatus.ERROR) DashboardCache.calendar(context, month) else null
                    value = cached ?: it
                }
            }
            val weather by produceState(initialWeather, revision, sample) {
                if (!sample) BreezyWeatherRepository.observe(context).collect {
                    DashboardCache.saveWeather(context, it)
                    val cached = if (it.status == org.monthlane.domain.model.WeatherStatus.ERROR) DashboardCache.weather(context) else null
                    value = cached ?: it
                }
            }
            val tasks by produceState(initialTasks ?: TasksSnapshot(TasksStatus.LOADING), revision, taskRevision, sample) {
                if (!sample) TasksRepository.observe(context).collect {
                    DashboardCache.saveTasks(context, it)
                    val cached = if (it.status == TasksStatus.ERROR) DashboardCache.tasks(context) else null
                    value = cached ?: it
                }
            }
            val completed = prefs[HomeWidgetState.completed] ?: emptySet()
            val components = prefs[HomeWidgetState.components] ?: WidgetComponents.all
            val dashboard = if (sample) MockDashboard.load(selected, completed = completed) else snapshot.dashboard(selected, completed).copy(weather = weather?.weather, weatherStatus = weather?.status ?: org.monthlane.domain.model.WeatherStatus.LOADING, tasks = tasks.tasks.filterNot { task -> org.monthlane.sync.PendingTasks.committed(context, task) }.map { task -> task.copy(completed = org.monthlane.sync.PendingTasks.displayed(context, task), writable = task.writable && !org.monthlane.sync.PendingTasks.uncertain(context, task) && (org.monthlane.sync.PendingTasks.contains(context, task.id) || !org.monthlane.sync.PendingTasks.displayed(context, task)), completionUncertain = org.monthlane.sync.PendingTasks.uncertain(context, task)) }, tasksStatus = tasks.status, tasksTotal = tasks.total)
            HomeLayout(dashboard, appearance, components = components, weatherStyle = prefs[HomeWidgetState.weatherStyle] ?: "standard")
        }
    }
}
class HomeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = HomeWidget()
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        CalendarRefreshWorker.ensureScheduled(context)
        org.monthlane.sync.TasksRefreshWorker.ensureScheduled(context)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
    override fun onDisabled(context: Context) {
        CalendarRefreshWorker.cancel(context)
        org.monthlane.sync.TasksRefreshWorker.cancel(context)
        super.onDisabled(context)
    }
}

// All updates go through the same managed session to avoid racing RemoteViews snapshots.
private val interactionMutex = kotlinx.coroutines.sync.Mutex()
private suspend fun publishInteraction(context: Context, glanceId: GlanceId) {
    HomeWidget().update(context, glanceId)
}

class SelectDayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val date = parameters[HomeWidgetState.dateParameter]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        interactionMutex.withLock {
            updateAppWidgetState(context, glanceId) { it[HomeWidgetState.selected] = date.toString(); it[HomeWidgetState.selectionDay] = LocalDate.now().toString() }
            publishInteraction(context, glanceId)
        }
    }
}
class MoveMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val movement = parameters[HomeWidgetState.moveParameter] ?: return
        if (movement !in -1..1) return
        interactionMutex.withLock {
        updateAppWidgetState(context, glanceId) {
            it[HomeWidgetState.selectionDay] = LocalDate.now().toString()
            it[HomeWidgetState.selected] = if (movement == 0) LocalDate.now().toString() else shiftSelection(HomeWidgetState.selectedDate(it), movement.toLong()).toString()
        }
        publishInteraction(context, glanceId)
        }
    }
}
private val taskActionMutex = kotlinx.coroutines.sync.Mutex()

class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[HomeWidgetState.taskParameter] ?: return
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        if (prefs[HomeWidgetState.sample] == true) {
            updateAppWidgetState(context, glanceId) {
                val selected = HomeWidgetState.selectedDate(it)
                if (MockDashboard.load(selected).tasks.none { task -> task.id == id }) return@updateAppWidgetState
                val toggled = (it[HomeWidgetState.completed] ?: emptySet()).toMutableSet()
                if (!toggled.add(id)) toggled.remove(id)
                it[HomeWidgetState.completed] = toggled
            }
        } else {
            val taskId = id.toLongOrNull() ?: return
            val due = parameters[HomeWidgetState.taskDueParameter] ?: return
            val modified = parameters[HomeWidgetState.taskModifiedParameter] ?: return
            taskActionMutex.withLock { org.monthlane.sync.PendingTasks.toggle(context, taskId, due, modified) }
            refreshTaskWidgets(context, reload = false, first = glanceId)
            return
        }
        interactionMutex.withLock { publishInteraction(context, glanceId) }
    }
}

/** Update persisted widget appearance so active Glance sessions recompose as well. */
suspend fun refreshAppearance(context: Context, appearance: WidgetAppearance) {
    GlanceAppWidgetManager(context).getGlanceIds(HomeWidget::class.java).forEach { id ->
        updateAppWidgetState(context, id) {
            it[HomeWidgetState.preset] = appearance.preset.name
            it[HomeWidgetState.mode] = appearance.mode.name
            it[HomeWidgetState.palette] = appearance.palette.name
        }
        HomeWidget().update(context, id)
    }
}

/** A revision invalidates active compositions even if only provider permission changed. */
suspend fun refreshCalendarWidgets(context: Context) {
    CalendarData.invalidate()
    val ids = GlanceAppWidgetManager(context).getGlanceIds(HomeWidget::class.java)
    ids.forEach { id ->
        updateAppWidgetState(context, id) { it[HomeWidgetState.revision] = android.os.SystemClock.elapsedRealtimeNanos() }
        HomeWidget().update(context, id)
    }
    if (ids.isNotEmpty()) { CalendarRefreshWorker.ensureScheduled(context); org.monthlane.sync.TasksRefreshWorker.ensureScheduled(context) }
    else { CalendarRefreshWorker.cancel(context); org.monthlane.sync.TasksRefreshWorker.cancel(context) }
}

/** Refresh task observations and completion state without restarting calendar or weather. */
suspend fun refreshTaskWidgets(context: Context, reload: Boolean = true, first: GlanceId? = null) {
    val ids = GlanceAppWidgetManager(context).getGlanceIds(HomeWidget::class.java)
    val ordered = if (first != null && first in ids) listOf(first) + ids.filterNot { it == first } else ids
    ordered.forEach { id ->
        updateAppWidgetState(context, id) {
            val key = if (reload) HomeWidgetState.taskRevision else HomeWidgetState.taskDisplayRevision
            it[key] = android.os.SystemClock.elapsedRealtimeNanos()
        }
        HomeWidget().update(context, id)
    }
}
