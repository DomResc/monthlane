package org.monthlane.widget

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.monthlane.R
import org.monthlane.data.mock.MockDashboard
import org.monthlane.domain.model.CalendarStatus
import org.monthlane.domain.model.TasksStatus
import org.monthlane.domain.model.WeatherStatus
import org.monthlane.theme.WidgetAppearance
import org.monthlane.widget.home.HomeLayout
import org.monthlane.widget.home.WidgetComponents
import org.monthlane.widget.home.localizedMessage

@RunWith(AndroidJUnit4::class)
class WidgetProviderMessageTest {
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun missingAndUnconnectedProvidersPointToTheApp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val expected = context.getString(R.string.widget_connect_provider)
        assertTrue(CalendarStatus.PERMISSION_REQUIRED.localizedMessage(context) == expected)
        for ((tasksStatus, weatherStatus) in listOf(
            TasksStatus.MISSING to WeatherStatus.MISSING_APP,
            TasksStatus.PERMISSION to WeatherStatus.PERMISSION_REQUIRED,
        )) {
            val state = MockDashboard.load(MockDashboard.referenceDate).copy(
                calendarStatus = CalendarStatus.READY, tasksStatus = tasksStatus,
                tasks = emptyList(), weatherStatus = weatherStatus, weather = null,
            )
            val views = GlanceRemoteViews().compose(context, DpSize(336.dp, 580.dp)) {
                HomeLayout(state, WidgetAppearance(), interactive = false,
                    components = setOf(WidgetComponents.WEATHER, WidgetComponents.TASKS))
            }.remoteViews
            var found = 0
            instrumentation.runOnMainSync {
                val frame = FrameLayout(context)
                val root = views.apply(context, frame)
                fun count(view: View): Int =
                    (if (view is TextView && view.text.toString() == expected) 1 else 0) +
                        (if (view is ViewGroup) (0 until view.childCount).sumOf { count(view.getChildAt(it)) } else 0)
                found = count(root)
            }
            assertTrue("Both providers should direct users to the app", found >= 2)
        }
    }
}
