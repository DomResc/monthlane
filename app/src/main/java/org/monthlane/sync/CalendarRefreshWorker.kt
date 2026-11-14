package org.monthlane.sync

import android.content.Context
import android.provider.CalendarContract
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.*
import kotlinx.coroutines.CancellationException
import org.monthlane.data.calendar.CalendarSettings
import org.monthlane.widget.home.HomeWidget
import org.monthlane.widget.home.refreshCalendarWidgets
import java.util.concurrent.TimeUnit

/** Re-arm a content-triggered job after each change; no polling or network is needed. */
class CalendarRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (GlanceAppWidgetManager(applicationContext).getGlanceIds(HomeWidget::class.java).isEmpty()) return Result.success()
        return try {
            refreshCalendarWidgets(applicationContext)
            if (CalendarSettings.canRead(applicationContext)) enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            Result.retry()
        }
    }
    companion object {
        private const val NAME = "calendar-provider-changes"
        fun ensureScheduled(context: Context) {
            if (CalendarSettings.canRead(context)) enqueue(context, ExistingWorkPolicy.KEEP) else cancel(context)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(10, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy,
                OneTimeWorkRequestBuilder<CalendarRefreshWorker>().setConstraints(constraints).build())
        }
    }
}
