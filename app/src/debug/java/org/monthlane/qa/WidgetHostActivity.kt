package org.monthlane.qa

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.core.content.edit
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.monthlane.data.mock.MockDashboard
import org.monthlane.theme.*
import org.monthlane.widget.home.*

/** Debug-only system AppWidgetHost for repeatable launcher-size and interaction checks. */
class WidgetHostActivity : ComponentActivity() {
    private lateinit var host: AppWidgetHost
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, 26031)
        val appearance = WidgetAppearance(
            WidgetPreset.entries.firstOrNull { it.name == intent.getStringExtra("preset") } ?: WidgetPreset.MINIMAL,
            ThemeMode.entries.firstOrNull { it.name == intent.getStringExtra("theme") } ?: ThemeMode.LIGHT,
            Palette.entries.firstOrNull { it.name == intent.getStringExtra("palette") } ?: Palette.SAGE,
        )
        AppearanceStore.save(this, appearance)
        val root = FrameLayout(this).apply { setBackgroundColor(if (appearance.mode == ThemeMode.DARK) Color.rgb(34, 39, 36) else Color.rgb(223, 226, 217)) }
        setContentView(root)
        val manager = AppWidgetManager.getInstance(this)
        val prefs = getSharedPreferences("qa_host", MODE_PRIVATE)
        var widgetId = prefs.getInt("id", -1)
        if (widgetId == -1 || manager.getAppWidgetInfo(widgetId) == null) {
            widgetId = host.allocateAppWidgetId()
            if (!manager.bindAppWidgetIdIfAllowed(widgetId, ComponentName(this, HomeWidgetReceiver::class.java))) {
                host.deleteAppWidgetId(widgetId)
                root.addView(TextView(this).apply { text = "Allow debug widget binding with: adb shell appwidget grantbind --package org.monthlane --user 0" })
                return
            }
            prefs.edit { putInt("id", widgetId) }
        }
        val width = intent.getIntExtra("width", 336).coerceIn(100, 420)
        val height = intent.getIntExtra("height", 580).coerceIn(60, 700)
        val view = host.createView(this, widgetId, manager.getAppWidgetInfo(widgetId))
        view.setPadding(0, 0, 0, 0)
        view.updateAppWidgetSize(Bundle(), listOf(SizeF(width.toFloat(), height.toFloat())))
        val density = resources.displayMetrics.density
        root.addView(view, FrameLayout.LayoutParams((width * density).toInt(), (height * density).toInt(), Gravity.CENTER))
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(widgetId)
        lifecycleScope.launch {
            updateAppWidgetState(this@WidgetHostActivity, glanceId) {
                it[HomeWidgetState.sample] = intent.getStringExtra("source") != "DEVICE"
                intent.getStringExtra("components")?.let { value -> it[HomeWidgetState.components] = value.split(",").toSet() }
                intent.getStringExtra("weatherStyle")?.let { value -> it[HomeWidgetState.weatherStyle] = value }
                it[HomeWidgetState.revision] = android.os.SystemClock.elapsedRealtimeNanos()
            }
            if (intent.getBooleanExtra("reset", false)) {
                updateAppWidgetState(this@WidgetHostActivity, glanceId) {
                    it[HomeWidgetState.selected] = MockDashboard.referenceDate.toString()
                    it[HomeWidgetState.completed] = emptySet()
                }
            }
            refreshAppearance(this@WidgetHostActivity, appearance)
            HomeWidget().update(this@WidgetHostActivity, glanceId)
        }
    }
    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop() { host.stopListening(); super.onStop() }
}
