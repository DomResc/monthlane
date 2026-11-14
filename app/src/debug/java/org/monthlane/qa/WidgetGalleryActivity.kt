package org.monthlane.qa

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.monthlane.data.mock.MockDashboard
import org.monthlane.theme.*
import org.monthlane.widget.home.HomeLayout

// Debug-only surface: the exact Glance layout translated to Android RemoteViews.
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class WidgetGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preset = WidgetPreset.entries.firstOrNull { it.name == intent.getStringExtra("preset") } ?: WidgetPreset.MINIMAL
        val mode = ThemeMode.entries.firstOrNull { it.name == intent.getStringExtra("theme") } ?: ThemeMode.LIGHT
        val palette = Palette.entries.firstOrNull { it.name == intent.getStringExtra("palette") } ?: Palette.SAGE
        val width = intent.getIntExtra("width", 336).coerceIn(250, 420)
        val height = intent.getIntExtra("height", 580).coerceIn(320, 700)
        val root = FrameLayout(this).apply { setBackgroundColor(if (mode == ThemeMode.DARK) Color.rgb(34, 39, 36) else Color.rgb(223, 226, 217)) }
        setContentView(root)
        lifecycleScope.launch {
            val views = GlanceRemoteViews().compose(this@WidgetGalleryActivity, DpSize(width.dp, height.dp)) {
                HomeLayout(MockDashboard.load(MockDashboard.referenceDate, today = MockDashboard.referenceDate), WidgetAppearance(preset, mode, palette), interactive = false, fixedClock = true)
            }.remoteViews
            val density = resources.displayMetrics.density
            root.addView(views.apply(this@WidgetGalleryActivity, root), FrameLayout.LayoutParams((width * density).toInt(), (height * density).toInt(), Gravity.CENTER))
        }
    }
}
