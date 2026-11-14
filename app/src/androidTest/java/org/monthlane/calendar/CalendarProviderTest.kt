package org.monthlane.calendar

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.monthlane.data.calendar.*
import org.monthlane.domain.model.*
import org.monthlane.settings.CalendarEventActivity
import org.monthlane.qa.WidgetHostActivity
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class CalendarProviderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private var calendarId = -1L
    private var originalSelection: Set<Long>? = null
    private val month = YearMonth.of(2026, 11)

    @Before fun setup() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        originalSelection = CalendarSettings.selectedIds(context)
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, "Monthlane integration tests")
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "monthlane-test-${System.nanoTime()}")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Monthlane integration tests")
            put(CalendarContract.Calendars.OWNER_ACCOUNT, "monthlane-test")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF476745.toInt())
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        calendarId = ContentUris.parseId(requireNotNull(resolver.insert(syncUri(), values)))
        CalendarSettings.select(context, setOf(calendarId))
    }
    @After fun cleanup() {
        val editor = context.getSharedPreferences(CalendarSettings.FILE, android.content.Context.MODE_PRIVATE).edit()
        if (originalSelection == null) editor.remove(CalendarSettings.SELECTED_IDS)
        else editor.putStringSet(CalendarSettings.SELECTED_IDS, originalSelection!!.map(Long::toString).toSet())
        assertTrue(editor.commit())
        if (calendarId >= 0) resolver.delete(syncUri(calendarId), null, null)
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }
    private fun syncUri(id: Long? = null) = (if (id == null) CalendarContract.Calendars.CONTENT_URI else ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id)).buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "Monthlane integration tests")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    private fun insert(title: String, start: String = "2026-11-12T09:30:00Z", end: String = "2026-11-12T10:30:00Z", allDay: Boolean = false, recurring: Boolean = false, cancelled: Boolean = false): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, "Local test description")
            put(CalendarContract.Events.EVENT_LOCATION, "Test room")
            put(CalendarContract.Events.DTSTART, Instant.parse(start).toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.STATUS, if (cancelled) CalendarContract.Events.STATUS_CANCELED else CalendarContract.Events.STATUS_CONFIRMED)
            if (recurring) {
                put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=3")
                put(CalendarContract.Events.DURATION, "PT3600S")
            } else put(CalendarContract.Events.DTEND, Instant.parse(end).toEpochMilli())
        }
        return ContentUris.parseId(requireNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, values)))
    }

    @Test fun readsExpandedRecurrencesAndExcludesCancelledAndDeletedEvents() = runBlocking {
        val recurring = insert("Weekly review", recurring = true)
        insert("Cancelled", cancelled = true)
        val removed = insert("Deleted")
        resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, removed), null, null)
        val snapshot = CalendarData.loadMonth(context, month, ZoneOffset.UTC)
        assertEquals(CalendarStatus.READY, snapshot.status)
        assertEquals(3, snapshot.events.size)
        assertEquals(setOf(recurring), snapshot.events.map { it.providerId }.toSet())
        assertEquals(3, snapshot.events.map { it.id }.distinct().size)
        assertEquals(listOf(12, 19, 26), snapshot.events.map { it.start.atZone(ZoneOffset.UTC).dayOfMonth })
        assertTrue(snapshot.events.all { it.location == "Test room" && it.description == "Local test description" })
    }

    @Test fun respectsExplicitEmptySelectionAndRestoresDeviceVisibilityDefaults() = runBlocking {
        insert("Visible calendar event")
        CalendarSettings.select(context, emptySet())
        assertEquals(CalendarStatus.NO_SELECTION, CalendarData.loadMonth(context, month).status)
        assertTrue(CalendarData.loadMonth(context, month).events.isEmpty())
        CalendarSettings.select(context, null)
        assertTrue(CalendarData.loadMonth(context, month).events.any { it.title == "Visible calendar event" })
    }


    @Test fun defaultsExcludeHiddenCalendarsButExplicitSelectionCanIncludeThem() = runBlocking {
        insert("Hidden calendar event")
        resolver.update(syncUri(calendarId), ContentValues().apply { put(CalendarContract.Calendars.VISIBLE, 0) }, null, null)
        CalendarSettings.select(context, null)
        assertTrue(CalendarData.loadMonth(context, month).events.none { it.title == "Hidden calendar event" })
        CalendarSettings.select(context, setOf(calendarId))
        assertTrue(CalendarData.loadMonth(context, month).events.any { it.title == "Hidden calendar event" })
    }

    @Test fun readsAllDayAndOvernightEventsWithExclusiveEnds() = runBlocking {
        insert("Two day event", "2026-11-12T00:00:00Z", "2026-11-14T00:00:00Z", allDay = true)
        insert("Overnight", "2026-11-11T23:30:00Z", "2026-11-12T01:00:00Z")
        val snapshot = CalendarData.loadMonth(context, month, ZoneOffset.UTC)
        val date = LocalDate.of(2026, 11, 12)
        assertEquals(listOf("Two day event", "Overnight"), eventsOnDate(snapshot.events, date, ZoneOffset.UTC).map { it.title })
        assertEquals(listOf("Two day event"), eventsOnDate(snapshot.events, date.plusDays(1), ZoneOffset.UTC).map { it.title })
        assertTrue(eventsOnDate(snapshot.events, date.plusDays(2), ZoneOffset.UTC).isEmpty())
    }

    @Test fun observesProviderEditsWithoutPolling() = runBlocking {
        val event = insert("Before editing")
        val channel = Channel<CalendarSnapshot>(Channel.UNLIMITED)
        val job = launch { CalendarData.observeMonth(context, month).collect { channel.send(it) } }
        try {
            val initial = withTimeout(10000) { channel.receive() }
            assertTrue(initial.events.any { it.title == "Before editing" })
            resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event), ContentValues().apply { put(CalendarContract.Events.TITLE, "After editing") }, null, null)
            withTimeout(10000) {
                while (channel.receive().events.none { it.title == "After editing" }) { /* Wait for the provider notification. */ }
            }
        } finally { job.cancelAndJoin(); channel.close() }
    }


    @Test fun reusesMonthlySnapshotAndInvalidatesSelection() = runBlocking {
        insert("Cached monthly event")
        delay(500)
        CalendarData.invalidate()
        val first = CalendarData.month(context, month, ZoneOffset.UTC)
        val second = CalendarData.month(context, month, ZoneOffset.UTC)
        assertSame(first, second)
        val firstDay = month.atDay(12)
        assertSame(first.dashboard(firstDay).indicators, first.dashboard(firstDay.plusDays(1)).indicators)
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        repeat(100) { index -> CalendarData.month(context, month, ZoneOffset.UTC).dashboard(month.atDay(index % month.lengthOfMonth() + 1)) }
        android.util.Log.i("MonthlaneQA", "100 cached daily snapshots: ${(android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0} ms")
        assertTrue(second.events.any { it.title == "Cached monthly event" })
        CalendarSettings.select(context, emptySet())
        assertEquals(CalendarStatus.NO_SELECTION, CalendarData.month(context, month, ZoneOffset.UTC).status)
        CalendarSettings.select(context, setOf(calendarId))
        assertTrue(CalendarData.month(context, month, ZoneOffset.UTC).events.any { it.title == "Cached monthly event" })
    }

    @Test fun resolvesItalianResourcesWithoutTranslatingCalendarNames() {
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocales(android.os.LocaleList.forLanguageTags("it-IT"))
        val italian = context.createConfigurationContext(configuration)
        assertEquals("Calendario", italian.getString(org.monthlane.R.string.calendar))
        assertEquals("Tutto il giorno", italian.getString(org.monthlane.R.string.all_day))
        assertEquals("2 eventi", italian.resources.getQuantityString(org.monthlane.R.plurals.event_count, 2, 2))
        val source = kotlinx.coroutines.runBlocking { CalendarData.loadMonth(context, month) }.calendars.single { it.id == calendarId }
        assertEquals("Monthlane integration tests", source.name)
    }

    @Test fun clearsOldSelectionBackgroundAcrossRepeatedRemoteViewsUpdates() = runBlocking {
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package org.monthlane --user 0").use {
            java.io.FileInputStream(it.fileDescriptor).use { output -> output.readBytes() }
        }
        for (day in listOf(18, 21, 25, 14)) insert("Selection day $day", "2026-11-${day}T09:00:00Z", "2026-11-${day}T10:00:00Z")
        CalendarData.invalidate()
        val host = instrumentation.startActivitySync(Intent(context, WidgetHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("reset", true).putExtra("theme", "DARK")
            .putExtra("preset", "MATERIAL").putExtra("palette", "SAGE").putExtra("source", "DEVICE").putExtra("components", "calendar,events"))
        fun find(view: android.view.View, text: String): android.widget.TextView? {
            if (view is android.widget.TextView && view.text.toString() == text) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                find(view.getChildAt(index), text)?.let { return it }
            }
            return null
        }
        suspend fun waitForHeading(day: Int) {
            val date = java.time.LocalDate.of(2026, 11, day)
            val heading = if (date == java.time.LocalDate.now()) context.getString(org.monthlane.R.string.today)
                else date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", context.resources.configuration.locales[0]))
            withTimeout(15000) {
                while (true) {
                    var ready = false
                    instrumentation.runOnMainSync {
                        val root = host.window.decorView
                        // The calendar's Today button can appear before today's agenda updates.
                        ready = find(root, heading) != null && (day == 12 || find(root, "Selection day $day") != null)
                    }
                    if (ready) break
                    delay(50)
                }
            }
        }
        try {
            waitForHeading(12)
            val widgetId = context.getSharedPreferences("qa_host", android.content.Context.MODE_PRIVATE).getInt("id", -1)
            val glanceId = androidx.glance.appwidget.GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
            val density = context.resources.displayMetrics.density
            val accent = org.monthlane.theme.widgetColors(context, org.monthlane.theme.WidgetAppearance(
                org.monthlane.theme.WidgetPreset.MATERIAL, org.monthlane.theme.ThemeMode.DARK, org.monthlane.theme.Palette.SAGE)).accent
            val expected = android.graphics.Color.argb((accent.alpha * 255).toInt(), (accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt())
            val visited = mutableListOf(12)
            for (day in listOf(18, 21, 25, 14)) {
                val started = android.os.SystemClock.elapsedRealtimeNanos()
                org.monthlane.widget.home.SelectDayAction().onAction(context, glanceId,
                    androidx.glance.action.actionParametersOf(org.monthlane.widget.home.HomeWidgetState.dateParameter to "2026-11-$day"))
                waitForHeading(day)
                android.util.Log.i("MonthlaneQA", "Day $day visible after ${(android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0} ms")
                instrumentation.waitForIdleSync()
                delay(100)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                instrumentation.runOnMainSync {
                    val root = host.window.decorView
                    fun color(number: Int): Int {
                        val label = requireNotNull(find(root, number.toString()))
                        val position = IntArray(2).also(label::getLocationOnScreen)
                        return bitmap.getPixel(position[0] + label.width / 2 + (8 * density).toInt(), position[1] + label.height / 2)
                    }
                    assertEquals("Selected day $day must have the accent background", expected, color(day))
                    visited.forEach { previous -> assertNotEquals("Old day $previous retained its highlight", expected, color(previous)) }
                    bitmap.recycle()
                }
                visited.add(day)
            }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun actualWidgetTapPreservesTypedOccurrenceExtras() = runBlocking {
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package org.monthlane --user 0").use {
            java.io.FileInputStream(it.fileDescriptor).use { output -> output.readBytes() }
        }
        val id = insert("Widget event contract")
        val event = CalendarData.loadMonth(context, month, ZoneOffset.UTC).events.single { it.providerId == id }
        val captured = CompletableDeferred<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW && intent.data?.authority == CalendarContract.AUTHORITY) {
                    captured.complete(Intent(intent))
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        var host: Activity? = null
        try {
            host = instrumentation.startActivitySync(Intent(context, WidgetHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("source", "DEVICE").putExtra("components", "events").putExtra("reset", true))
            var target: AccessibilityNodeInfo? = null
            val visibleText = mutableSetOf<String>()
            fun inspect(node: AccessibilityNodeInfo?) {
                if (node == null) return
                node.text?.toString()?.let(visibleText::add)
                if (node.text?.toString() == "Widget event contract") target = node
                for (child in 0 until node.childCount) inspect(node.getChild(child))
            }
            withTimeoutOrNull(30000) {
                while (target == null) {
                    inspect(instrumentation.uiAutomation.rootInActiveWindow)
                    if (target == null) delay(100)
                }
            }
            assertNotNull("Widget event missing; visible text: $visibleText", target)
            val bounds = android.graphics.Rect().also { requireNotNull(target).getBoundsInScreen(it) }
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val tap = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                assertTrue(instrumentation.uiAutomation.injectInputEvent(tap, true))
                tap.recycle()
            }
            val opened = withTimeout(10000) { captured.await() }
            assertEquals(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), opened.data)
            assertEquals(event.start.toEpochMilli(), opened.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
            assertEquals(event.end.toEpochMilli(), opened.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
        } finally {
            instrumentation.removeMonitor(monitor)
            host?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    @Test fun opensTheSelectedOccurrenceWithTheCalendarIntentContract() = runBlocking {
        val id = insert("Event to open", recurring = true)
        val event = CalendarData.loadMonth(context, month, ZoneOffset.UTC).events.filter { it.providerId == id }[1]
        val captured = CompletableDeferred<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW && intent.data?.authority == CalendarContract.AUTHORITY) {
                    captured.complete(Intent(intent))
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            context.startActivity(CalendarEventActivity.intent(context, event).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val opened = withTimeout(10000) { captured.await() }
            assertEquals(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), opened.data)
            assertEquals(event.start.toEpochMilli(), opened.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
            assertEquals(event.end.toEpochMilli(), opened.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
