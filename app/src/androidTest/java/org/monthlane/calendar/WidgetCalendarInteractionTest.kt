package org.monthlane.calendar

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetCalendarInteractionTest {
    @Test fun selectsDayAfterWidgetSessionExpires() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun find(description: String? = null, text: String? = null, resource: String? = null): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if ((description != null && node.contentDescription?.toString()?.contains(description) == true) ||
                    (text != null && node.text?.toString()?.contains(text) == true) ||
                    (resource != null && node.viewIdResourceName == resource)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> visit(child)?.let { return it } }
                return null
            }
            return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        }
        fun waitFor(timeout: Long = 15000, predicate: () -> Boolean): Boolean {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                if (predicate()) return true
                SystemClock.sleep(100)
            }
            return false
        }
        val intent = Intent().setClassName(context.packageName, "org.monthlane.qa.WidgetHostActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("source", "SAMPLE").putExtra("components", "clock,weather,calendar,events")
            .putExtra("height", 580).putExtra("width", 336).putExtra("reset", true)
        val activity = instrumentation.startActivitySync(intent)
        try {
            assertTrue("Calendar did not load", waitFor { find(description = "Friday 13 November 2026") != null })
            assertTrue("Clock did not load", waitFor { find(resource = "org.monthlane:id/widget_clock") != null })
            SystemClock.sleep(50000)
            val originalBounds = android.graphics.Rect()
            requireNotNull(find(description = "Friday 13 November 2026")).getBoundsInScreen(originalBounds)
            var day = requireNotNull(find(description = "Friday 13 November 2026"))
            while (!day.isClickable) day = requireNotNull(day.parent)
            val start = SystemClock.elapsedRealtime()
            assertTrue(day.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var changed = false
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline && !changed) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null && root.packageName == context.packageName) {
                    assertNotNull("Clock disappeared during selection", find(resource = "org.monthlane:id/widget_clock"))
                    val current = requireNotNull(find(description = "Friday 13 November 2026"))
                    val bounds = android.graphics.Rect()
                    current.getBoundsInScreen(bounds)
                    assertEquals("Calendar moved during selection", originalBounds, bounds)
                    changed = find(text = "Friday 13 November") != null
                }
                if (!changed) SystemClock.sleep(50)
            }
            assertTrue("Selected day did not update", changed)
            val elapsed = SystemClock.elapsedRealtime() - start
            println("MONTHLANE_QA calendar_cold_ms=$elapsed")
            assertTrue("Calendar feedback took ${elapsed}ms", elapsed < 5000)
            val settled = SystemClock.elapsedRealtime() + 2000
            while (SystemClock.elapsedRealtime() < settled) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null && root.packageName == context.packageName) {
                    assertNotNull("Clock disappeared after selection", find(resource = "org.monthlane:id/widget_clock"))
                    val current = requireNotNull(find(description = "Friday 13 November 2026"))
                    val bounds = android.graphics.Rect()
                    current.getBoundsInScreen(bounds)
                    assertEquals("Calendar moved after selection", originalBounds, bounds)
                }
                SystemClock.sleep(50)
            }
        } finally { activity.finish() }
    }
}
