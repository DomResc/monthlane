package org.monthlane.sync

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.*
import kotlinx.coroutines.CancellationException
import org.monthlane.data.tasks.TasksRepository
import org.monthlane.widget.home.HomeWidget
import org.monthlane.widget.home.refreshTaskWidgets
import java.util.concurrent.TimeUnit

class TasksRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (GlanceAppWidgetManager(applicationContext).getGlanceIds(HomeWidget::class.java).isEmpty()) return Result.success()
        return try {
            refreshTaskWidgets(applicationContext)
            if (TasksRepository.available(applicationContext) && TasksRepository.canRead(applicationContext)) enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { Result.retry() }
    }
    companion object {
        private const val NAME = "tasks-provider-changes-v2"
        private const val OLD_NAME = "tasks-provider-changes"
        fun ensureScheduled(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(OLD_NAME)
            if (TasksRepository.available(context) && TasksRepository.canRead(context)) enqueue(context, ExistingWorkPolicy.KEEP) else cancel(context)
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(OLD_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder().addContentUriTrigger(android.net.Uri.parse("content://org.tasks.api/v0"), true)
                .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS).setTriggerContentMaxDelay(10, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy,
                OneTimeWorkRequestBuilder<TasksRefreshWorker>().setConstraints(constraints).build())
        }
    }
}
