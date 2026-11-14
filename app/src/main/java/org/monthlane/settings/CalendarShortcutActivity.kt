package org.monthlane.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId

/** Opens calendar shortcuts from a user-initiated widget tap. */
class CalendarShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data
        val date = runCatching { LocalDate.parse(data?.lastPathSegment) }.getOrNull()
        if (date == null) { finish(); return }
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val calendarIntent = if (data?.host == CREATE) {
            val eventStart = date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, eventStart)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, eventStart + 3_600_000)
        } else {
            Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(), start))
        }
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
        private const val CREATE = "create"
        fun intent(context: Context, date: LocalDate, create: Boolean): Intent =
            Intent(context, CalendarShortcutActivity::class.java)
                .setData(Uri.Builder().scheme("monthlane").authority(if (create) CREATE else "view").appendPath(date.toString()).build())
    }
}
