package org.monthlane.widget.home

import android.content.Context
import org.monthlane.R
import org.monthlane.domain.model.CalendarStatus

fun CalendarStatus.localizedMessage(context: Context): String? = when (this) {
    CalendarStatus.SAMPLE, CalendarStatus.READY -> null
    CalendarStatus.LOADING -> context.getString(R.string.calendar_loading)
    CalendarStatus.PERMISSION_REQUIRED -> context.getString(R.string.widget_connect_provider)
    CalendarStatus.NO_CALENDARS -> context.getString(R.string.calendar_none)
    CalendarStatus.NO_SELECTION -> context.getString(R.string.calendar_selection)
    CalendarStatus.ERROR -> context.getString(R.string.calendar_error)
}
