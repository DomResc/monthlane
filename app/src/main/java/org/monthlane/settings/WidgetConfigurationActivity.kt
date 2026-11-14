package org.monthlane.settings

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.monthlane.R
import org.monthlane.widget.home.HomeWidget
import org.monthlane.widget.home.HomeWidgetState
import org.monthlane.widget.home.WidgetComponents

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || provider?.packageName != packageName ||
            provider.className != org.monthlane.widget.home.HomeWidgetReceiver::class.java.name) { finish(); return }
        lifecycleScope.launch {
            val glanceId = runCatching { GlanceAppWidgetManager(this@WidgetConfigurationActivity).getGlanceIdBy(widgetId) }.getOrNull()
                ?: run { finish(); return@launch }
            val initialPrefs = getAppWidgetState(this@WidgetConfigurationActivity, PreferencesGlanceStateDefinition, glanceId)
            val initial = initialPrefs[HomeWidgetState.components] ?: WidgetComponents.all
            setContent {
                var selected by remember { mutableStateOf(initial) }
                var weatherStyle by remember { mutableStateOf(org.monthlane.domain.model.normalizedWeatherStyle(initialPrefs[HomeWidgetState.weatherStyle])) }
                val entries = listOf(WidgetComponents.CLOCK to R.string.component_clock, WidgetComponents.WEATHER to R.string.component_weather,
                    WidgetComponents.CALENDAR to R.string.component_calendar, WidgetComponents.EVENTS to R.string.component_events,
                    WidgetComponents.TASKS to R.string.component_tasks)
                val dark = isSystemInDarkTheme()
                val colors = if (dark) dynamicDarkColorScheme(this@WidgetConfigurationActivity) else dynamicLightColorScheme(this@WidgetConfigurationActivity)
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                MaterialTheme(colorScheme = colors) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.widget_components), style = MaterialTheme.typography.headlineSmall)
                            entries.forEach { (key, label) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(label), modifier = Modifier.weight(1f).padding(top = 12.dp))
                                    Switch(checked = key in selected, onCheckedChange = { checked ->
                                        selected = if (checked) selected + key else selected - key
                                    })
                                }
                            }
                            if (WidgetComponents.WEATHER in selected) {
                                Text(stringResource(R.string.weather_style))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("standard" to R.string.weather_style_standard, "compact" to R.string.weather_style_compact).forEach { (style, label) ->
                                        FilterChip(weatherStyle == style, { weatherStyle = style }, { Text(stringResource(label)) })
                                    }
                                }
                            }
                            Button(enabled = selected.isNotEmpty(), onClick = {
                                lifecycleScope.launch {
                                    updateAppWidgetState(this@WidgetConfigurationActivity, glanceId) { it[HomeWidgetState.components] = selected; it[HomeWidgetState.weatherStyle] = weatherStyle }
                                    HomeWidget().update(this@WidgetConfigurationActivity, glanceId)
                                    org.monthlane.sync.CalendarRefreshWorker.ensureScheduled(this@WidgetConfigurationActivity)
                                    org.monthlane.sync.TasksRefreshWorker.ensureScheduled(this@WidgetConfigurationActivity)
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                    finish()
                                }
                            }) { Text(stringResource(R.string.widget_save)) }
                        }
                    }
                }
            }
        }
    }
}
