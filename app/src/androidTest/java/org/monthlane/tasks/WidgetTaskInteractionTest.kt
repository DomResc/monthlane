package org.monthlane.tasks

import android.content.ContentValues
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetTaskInteractionTest {
    @Test fun opensAndCompletesRealTaskFromWidget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val uri = org.monthlane.data.tasks.TasksRepository.uri
        val title = "Monthlane interaction QA ${SystemClock.elapsedRealtime()}"
        val item = requireNotNull(context.contentResolver.insert(uri, ContentValues().apply { put("title", title); put("priority", "low") }))
        val tags = android.net.Uri.parse("content://org.tasks.api/v0/tags")
        val tagName = "Monthlane widget color QA ${SystemClock.elapsedRealtime()}"
        val tag = requireNotNull(context.contentResolver.insert(tags, ContentValues().apply {
            put("name", tagName)
            put("color", 0xFF4CAF50.toInt())
        }))
        var activity: android.app.Activity? = null
        fun find(description: String? = null, text: String? = null): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if ((description != null && node.contentDescription?.toString()?.contains(description) == true) ||
                    (text != null && node.text?.toString()?.contains(text) == true)) return node
                for (i in 0 until node.childCount) {
                    val result = node.getChild(i)?.let(::visit)
                    if (result != null) return result
                }
                return null
            }
            return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        }
        fun waitFor(timeout: Long = 15000, predicate: () -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return true
                SystemClock.sleep(100)
            }
            return false
        }
        try {
            requireNotNull(context.contentResolver.insert(android.net.Uri.parse("content://org.tasks.api/v0/task_tags"), ContentValues().apply {
                put("task_id", android.content.ContentUris.parseId(item))
                put("tag_id", android.content.ContentUris.parseId(tag))
            }))
            val intent = Intent().setClassName(context.packageName, "org.monthlane.qa.WidgetHostActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("source", "DEVICE").putExtra("components", "tasks")
                .putExtra("height", 320).putExtra("width", 336)
            activity = instrumentation.startActivitySync(intent)
            assertTrue("Task not shown in widget", waitFor { find(text = title) != null })
            assertTrue("Tag not shown in widget", waitFor { find(text = tagName) != null })
            val checkbox = requireNotNull(find(description = title))
            fun checkable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isCheckable) return node
                for (i in 0 until node.childCount) checkable(node.getChild(i))?.let { return it }
                return null
            }
            val action = checkable(checkbox)
            val bounds = android.graphics.Rect()
            requireNotNull(action).getBoundsInScreen(bounds)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                var bluePixels = 0
                for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(screenshot.height)) {
                    for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(screenshot.width)) {
                        val pixel = screenshot.getPixel(x, y)
                        if (kotlin.math.abs(android.graphics.Color.red(pixel) - 0x63) <= 3 &&
                            kotlin.math.abs(android.graphics.Color.green(pixel) - 0xB0) <= 3 &&
                            kotlin.math.abs(android.graphics.Color.blue(pixel) - 0xFF) <= 3) bluePixels++
                    }
                }
                assertTrue("Low-priority checkbox is not blue", bluePixels > 0)
            } finally { screenshot.recycle() }
            val checkStart = SystemClock.elapsedRealtime()
            assertTrue("Task checkbox is not clickable", action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            var sawChecked = false
            val removed = waitFor {
                val node = find(description = title)
                if (checkable(node)?.isChecked == true || find(description = "Completed: $title") != null) sawChecked = true
                find(text = title) == null
            }
            assertTrue("Task remained visible", removed)
            val checkMs = SystemClock.elapsedRealtime() - checkStart
            assertTrue("No visible checked state before removal", sawChecked)
            assertTrue("Task was not completed in Tasks.org", waitFor {
                context.contentResolver.query(item, arrayOf("completed_at"), null, null, null)?.use {
                    it.moveToFirst() && it.getLong(0) > 0L
                } == true
            })
            println("MONTHLANE_QA check_ms=$checkMs")
            assertTrue("Completion feedback took ${checkMs}ms", checkMs < 5000)
        } finally {
            activity?.finish()
            context.contentResolver.delete(item, null, null)
            context.contentResolver.delete(tag, null, null)
        }
    }
}
