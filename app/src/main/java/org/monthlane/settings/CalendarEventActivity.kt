package org.monthlane.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import org.monthlane.domain.model.CalendarEvent

/** An activity PendingIntent preserves Android's user-initiated launch semantics for widgets. */
class CalendarEventActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EVENT_ID, -1)
        if (id < 0) { finish(); return }
        val calendarIntent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, intent.getLongExtra(BEGIN, 0))
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, intent.getLongExtra(END, 0))
        try {
            startActivity(calendarIntent)
        } catch (_: ActivityNotFoundException) {
            unavailable()
        } catch (_: SecurityException) {
            unavailable()
        }
        finish()
    }
    private fun unavailable() {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("event_open_error", true))
    }
    companion object {
        private const val EVENT_ID = "event_id"
        private const val BEGIN = "begin"
        private const val END = "end"
        fun intent(context: Context, event: CalendarEvent) = Intent(context, CalendarEventActivity::class.java)
            .putExtra(EVENT_ID, event.providerId ?: -1)
            .putExtra(BEGIN, event.start.toEpochMilli()).putExtra(END, event.end.toEpochMilli())
    }
}
