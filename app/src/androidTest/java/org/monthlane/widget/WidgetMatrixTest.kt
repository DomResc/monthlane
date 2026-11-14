package org.monthlane.widget

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.monthlane.data.mock.MockDashboard
import org.monthlane.theme.ThemeMode
import org.monthlane.theme.WidgetAppearance
import org.monthlane.theme.WidgetPreset
import org.monthlane.widget.home.HomeLayout
import org.monthlane.widget.home.WidgetComponents
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class WidgetMatrixTest {
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun allComponentCombinationsAtLauncherSizes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val composer = GlanceRemoteViews()
        val keys = listOf(WidgetComponents.CLOCK, WidgetComponents.WEATHER, WidgetComponents.CALENDAR, WidgetComponents.EVENTS, WidgetComponents.TASKS)
        val sample = MockDashboard.load(MockDashboard.referenceDate, today = LocalDate.of(2026, 11, 2))
        val credit = "Test source (CC BY 4.0)"
        val dashboard = sample.copy(weather = requireNotNull(sample.weather).copy(sample = false, credits = listOf(credit)))
        var count = 0
        for (mask in 1 until (1 shl keys.size)) for (height in listOf(340, 480, 580))
            for (preset in WidgetPreset.entries) for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK))
                for (style in listOf("standard", "compact")) {
                    val components = keys.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
                    val id = "components=$components height=$height preset=$preset mode=$mode style=$style"
                    val views = composer.compose(context, DpSize(336.dp, height.dp)) {
                        HomeLayout(dashboard, WidgetAppearance(preset = preset, mode = mode), interactive = false, fixedClock = true, components = components, weatherStyle = style)
                    }.remoteViews
                    var problem: String? = null
                    instrumentation.runOnMainSync {
                        try {
                            val density = context.resources.displayMetrics.density
                            val widthPx = (336 * density).toInt()
                            val heightPx = (height * density).toInt()
                            val frame = FrameLayout(context)
                            val view = views.apply(context, frame)
                            frame.addView(view)
                            frame.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
                            frame.layout(0, 0, widthPx, heightPx)
                            fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
                            val nodes = descendants(frame)
                            fun shown(v: View): Boolean {
                                val rect = Rect(0, 0, v.width, v.height)
                                frame.offsetDescendantRectToMyCoords(v, rect)
                                return v.visibility == View.VISIBLE && rect.intersect(0, 0, widthPx, heightPx) && rect.width() > 0 && rect.height() > 0
                            }
                            val texts = nodes.filterIsInstance<TextView>().filter(::shown).map { it.text.toString() }
                            fun requireShown(condition: Boolean, what: String) { if (!condition) error("$what: $id") }
                            if (WidgetComponents.CLOCK in components) requireShown(nodes.filterIsInstance<TextClock>().any(::shown), "Clock absent")
                            if (WidgetComponents.WEATHER in components) {
                                requireShown(texts.any { it.contains("12°") }, "Weather absent")
                                requireShown(texts.none { it.contains(credit) }, "Weather attribution belongs in the app only")
                                requireShown(texts.none { it.contains("Milan") }, "Location displayed")
                                if (style == "standard") requireShown(texts.any { it.contains("7° / 16°") }, "Min/max absent")
                            }
                            if (WidgetComponents.CALENDAR in components) requireShown(texts.any { it.contains("November 2026") }, "Calendar absent")
                            if (WidgetComponents.EVENTS in components) requireShown(texts.any { it.contains("12 November") }, "Events absent")
                            if (WidgetComponents.TASKS in components) requireShown(texts.any { it.contains("Sample tasks") }, "Tasks absent")
                        } catch (error: Throwable) { problem = error.toString() }
                    }
                    assertNull(problem, problem)
                    count++
                }
        assertEquals(744, count)
        println("MONTHLANE_QA matrix_cases=$count")
    }
}
