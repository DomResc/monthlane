package org.monthlane.settings

import android.app.Activity
import androidx.core.view.WindowCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import org.monthlane.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.monthlane.data.calendar.CalendarData
import org.monthlane.data.calendar.CalendarSettings
import org.monthlane.data.calendar.CalendarSnapshot
import org.monthlane.domain.model.CalendarStatus
import org.monthlane.widget.home.refreshCalendarWidgets
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.YearMonth
import org.monthlane.theme.*
import org.monthlane.widget.home.refreshAppearance
import org.monthlane.data.weather.BreezyWeatherRepository
import org.monthlane.data.weather.WeatherSnapshot
import org.monthlane.data.cache.DashboardCache
import org.monthlane.domain.model.WeatherStatus
import org.monthlane.data.tasks.*
import org.monthlane.domain.model.TasksStatus

class SettingsActivity : ComponentActivity() {
    private var calendarRevision by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen(calendarRevision, intent.getBooleanExtra("event_open_error", false), intent.getBooleanExtra("weather_open_error", false), intent.getBooleanExtra("tasks_open_error", false), lifecycle) }
    }
    override fun onResume() {
        super.onResume()
        calendarRevision++
        lifecycleScope.launch { refreshCalendarWidgets(this@SettingsActivity) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(resumeRevision: Int, eventOpenError: Boolean, weatherOpenError: Boolean, tasksOpenError: Boolean, lifecycle: Lifecycle) {
    val context = LocalContext.current
    var appearance by remember { mutableStateOf(AppearanceStore.load(context)) }
    var calendarRevision by remember { mutableIntStateOf(0) }
    var selectionRevision by remember { mutableIntStateOf(0) }
    val selectedDate = LocalDate.now()
    val snapshot by produceState(CalendarSnapshot(CalendarStatus.LOADING), resumeRevision, calendarRevision) {
        value = CalendarSnapshot(if (CalendarSettings.canRead(context)) CalendarStatus.LOADING else CalendarStatus.PERMISSION_REQUIRED)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            CalendarData.observeMonth(context, YearMonth.from(selectedDate)).collect { value = it }
        }
    }
    val weatherSnapshot by produceState(DashboardCache.weather(context) ?: WeatherSnapshot(WeatherStatus.LOADING), resumeRevision, calendarRevision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            BreezyWeatherRepository.observe(context).collect {
                DashboardCache.saveWeather(context, it)
                value = if (it.status == WeatherStatus.ERROR) DashboardCache.weather(context) ?: it else it
            }
        }
    }
    val tasksSnapshot by produceState(TasksSnapshot(TasksStatus.LOADING), resumeRevision, calendarRevision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            TasksRepository.observe(context).collect { value = it }
        }
    }
    val scope = rememberCoroutineScope()
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark(context, appearance.mode)
                isAppearanceLightNavigationBars = !isDark(context, appearance.mode)
            }
        }
    }
    fun change(value: WidgetAppearance) {
        appearance = value
        AppearanceStore.save(context, value)
        scope.launch { refreshAppearance(context, value) }
    }
    MaterialTheme(colorScheme = widgetColorScheme(context, appearance)) {
        Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Monthlane", style = MaterialTheme.typography.headlineMedium)
            SettingsSection {
                Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.preset), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidgetPreset.entries.forEach { preset ->
                        FilterChip(appearance.preset == preset, { change(appearance.copy(preset = preset)) }, {
                            Text(stringResource(if (preset == WidgetPreset.MATERIAL) R.string.style_material else R.string.style_transparent))
                        })
                    }
                }
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(appearance.mode == mode, { change(appearance.copy(mode = mode)) }, {
                            Text(stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.mode_system; ThemeMode.LIGHT -> R.string.mode_light; ThemeMode.DARK -> R.string.mode_dark }))
                        })
                    }
                }
            }
            Text(stringResource(R.string.providers), style = MaterialTheme.typography.titleLarge)
            ProviderSection(stringResource(R.string.calendar), when (snapshot.status) {
                CalendarStatus.LOADING -> stringResource(R.string.provider_loading)
                CalendarStatus.READY -> stringResource(R.string.provider_connected)
                CalendarStatus.PERMISSION_REQUIRED -> stringResource(R.string.provider_setup)
                else -> stringResource(R.string.provider_attention)
            }, eventOpenError) {
                if (eventOpenError) Text(stringResource(R.string.event_open_error), color = MaterialTheme.colorScheme.error)
                CalendarControls(snapshot,
                    onPermissionResult = { calendarRevision++; scope.launch { refreshCalendarWidgets(context) } },
                    onSelection = { ids -> CalendarSettings.select(context, ids); selectionRevision++; scope.launch { refreshCalendarWidgets(context) } },
                    onRefresh = { calendarRevision++; scope.launch { refreshCalendarWidgets(context) } },
                    selectionRevision = selectionRevision)
            }
            ProviderSection(stringResource(R.string.weather_title), when (weatherSnapshot.status) {
                WeatherStatus.LOADING -> stringResource(R.string.provider_loading)
                WeatherStatus.READY -> stringResource(R.string.provider_connected)
                WeatherStatus.MISSING_APP, WeatherStatus.PERMISSION_REQUIRED -> stringResource(R.string.provider_setup)
                else -> stringResource(R.string.provider_attention)
            }, weatherOpenError) {
                if (weatherOpenError) Text(stringResource(R.string.weather_unavailable_app), color = MaterialTheme.colorScheme.error)
                WeatherControls(weatherSnapshot, onRefresh = { calendarRevision++; scope.launch { refreshCalendarWidgets(context) } })
            }
            ProviderSection(stringResource(R.string.provider_tasks_title), when (tasksSnapshot.status) {
                TasksStatus.LOADING -> stringResource(R.string.provider_loading)
                TasksStatus.READY -> stringResource(if (TasksRepository.canWrite(context)) R.string.provider_connected else R.string.provider_setup)
                TasksStatus.MISSING, TasksStatus.PERMISSION -> stringResource(R.string.provider_setup)
                else -> stringResource(R.string.provider_attention)
            }, tasksOpenError) {
                if (tasksOpenError) Text(stringResource(R.string.tasks_open_error), color = MaterialTheme.colorScheme.error)
                TasksControls(tasksSnapshot, onRefresh = { calendarRevision++; scope.launch { refreshCalendarWidgets(context) } })
            }
        }
    }
    }
}

@Composable
private fun SettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}


@Composable
private fun ProviderSection(title: String, status: String, initiallyExpanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val expandedDescription = stringResource(if (expanded) R.string.provider_expanded else R.string.provider_collapsed)
    SettingsSection {
        Row(Modifier.fillMaxWidth().semantics { stateDescription = expandedDescription }.clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) content()
    }
}
