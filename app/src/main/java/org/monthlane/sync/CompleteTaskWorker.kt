package org.monthlane.sync

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.monthlane.data.tasks.TasksRepository
import org.monthlane.widget.home.refreshTaskWidgets
import org.monthlane.domain.model.Task
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A pending completion is durable so the undo window survives widget session restarts. */
object PendingTasks {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processToken = UUID.randomUUID().toString()
    private fun prefs(context: Context) = context.getSharedPreferences("pending_tasks", Context.MODE_PRIVATE)
    fun contains(context: Context, id: String) = prefs(context).contains(id)
    private fun displayKey(id: String) = "display:$id"
    private fun attemptKey(id: String) = "attempt:$id"
    private fun displayEntry(context: Context, task: Task): JSONObject? {
        val entry = prefs(context).getString(displayKey(task.id), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return entry?.takeIf { it.optLong("due") == task.due && it.optLong("modified") == task.modified }
    }
    fun committed(context: Context, task: Task) = displayEntry(context, task)?.optBoolean("success") == true
    fun uncertain(context: Context, task: Task): Boolean {
        val entry = displayEntry(context, task) ?: return false
        return !entry.optBoolean("success") && entry.optString("owner") != processToken
    }
    fun displayed(context: Context, task: Task) = contains(context, task.id) ||
        (displayEntry(context, task) != null && !uncertain(context, task))
    private fun enqueue(context: Context, id: Long, token: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<CompleteTaskWorker>()
            .setInitialDelay(2, TimeUnit.SECONDS)
            .setInputData(workDataOf("id" to id, "token" to token)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("complete-task-$id", policy, request)
    }

    /** Re-arm durable work if the process stopped before the original enqueue completed. */
    suspend fun recover(context: Context) = mutex.withLock {
        prefs(context).all.forEach { (key, value) ->
            val id = key.toLongOrNull() ?: return@forEach
            val entry = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@forEach
            val token = entry.optString("token").takeIf { it.isNotBlank() } ?: return@forEach
            enqueue(context, id, token, ExistingWorkPolicy.KEEP)
        }
    }
    suspend fun toggle(context: Context, id: Long, due: Long, modified: Long) = mutex.withLock {
        val key = id.toString()
        val work = WorkManager.getInstance(context)
        if (prefs(context).contains(key)) {
            val pending = prefs(context).getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (pending == null || pending.optLong("due") != due || pending.optLong("modified") != modified) return@withLock
            prefs(context).edit().remove(key).commit()
            work.cancelUniqueWork("complete-task-$id")
        } else {
            val attempted = (prefs(context).getString(attemptKey(key), null) ?: prefs(context).getString(displayKey(key), null))
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (attempted != null && attempted.optLong("due") == due && attempted.optLong("modified") == modified) return@withLock
            if (attempted != null) prefs(context).edit().remove(attemptKey(key)).remove(displayKey(key)).commit()
            val token = UUID.randomUUID().toString()
            val value = JSONObject().put("token", token).put("due", due).put("modified", modified).toString()
            if (!prefs(context).edit().putString(key, value).commit()) return@withLock
            enqueue(context, id, token, ExistingWorkPolicy.REPLACE)
            val applicationContext = context.applicationContext
            scope.launch {
                delay(700)
                finishCompletion(applicationContext, id, token)
            }
        }
    }
    suspend fun complete(context: Context, id: Long, token: String): Boolean? = mutex.withLock {
        val key = id.toString()
        val value = prefs(context).getString(key, null) ?: return@withLock null
        val entry = runCatching { JSONObject(value) }.getOrNull() ?: return@withLock null
        if (entry.optString("token") != token) return@withLock null
        // Remove before writing: an ambiguous provider response must never trigger a retry.
        entry.put("owner", processToken)
        if (!prefs(context).edit().remove(key).putString(attemptKey(key), value).putString(displayKey(key), entry.toString()).commit()) return@withLock false
        val outcome = try {
            TasksRepository.completeResult(context, id, entry.getLong("due"), entry.getLong("modified"))
        } catch (error: Throwable) {
            prefs(context).edit().putString(displayKey(key), entry.put("owner", "").toString()).commit()
            throw error
        }
        when (outcome) {
            TasksRepository.CompletionResult.SUCCESS -> {
                prefs(context).edit().putString(displayKey(key), entry.put("success", true).toString()).commit()
                true
            }
            TasksRepository.CompletionResult.REJECTED -> {
                prefs(context).edit().remove(attemptKey(key)).remove(displayKey(key)).commit()
                false
            }
            TasksRepository.CompletionResult.UNKNOWN -> {
                prefs(context).edit().putString(displayKey(key), entry.put("owner", "").toString()).commit()
                false
            }
        }
    }
}

private suspend fun finishCompletion(context: Context, id: Long, token: String) {
    val result = PendingTasks.complete(context, id, token) ?: return
    if (!result) android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(context, org.monthlane.R.string.tasks_complete_error, android.widget.Toast.LENGTH_LONG).show()
    }
    refreshTaskWidgets(context)
}

class CompleteTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("id", -1)
        val token = inputData.getString("token") ?: return Result.failure()
        if (id < 0) return Result.failure()
        finishCompletion(applicationContext, id, token)
        return Result.success()
    }
}
