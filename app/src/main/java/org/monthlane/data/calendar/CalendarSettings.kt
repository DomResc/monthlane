package org.monthlane.data.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit

object CalendarSettings {
    const val FILE = "calendar_settings"
    const val SELECTED_IDS = "selected_ids"
    fun canRead(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun selectedIds(context: Context): Set<Long>? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return if (prefs.contains(SELECTED_IDS)) prefs.getStringSet(SELECTED_IDS, emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet() else null
    }
    fun select(context: Context, ids: Set<Long>?) {
        CalendarData.invalidate()
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            if (ids == null) remove(SELECTED_IDS) else putStringSet(SELECTED_IDS, ids.map(Long::toString).toSet())
        }
    }
}
