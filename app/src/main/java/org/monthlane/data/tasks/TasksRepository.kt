package org.monthlane.data.tasks

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.monthlane.domain.model.Task
import org.monthlane.domain.model.TaskLabel
import org.monthlane.domain.model.TasksStatus

object TasksRepository {
    const val READ = "org.tasks.permission.READ_TASKS"
    const val WRITE = "org.tasks.permission.WRITE_TASKS"
    val uri: Uri = Uri.parse("content://org.tasks.api/v0/tasks")
    fun available(context: Context) = context.packageManager.resolveContentProvider("org.tasks.api", 0) != null
    fun canRead(context: Context) = context.checkSelfPermission(READ) == PackageManager.PERMISSION_GRANTED
    fun canWrite(context: Context) = context.checkSelfPermission(WRITE) == PackageManager.PERMISSION_GRANTED

    suspend fun load(context: Context): TasksSnapshot = withContext(Dispatchers.IO) {
        if (!available(context)) return@withContext TasksSnapshot(TasksStatus.MISSING)
        if (!canRead(context)) return@withContext TasksSnapshot(TasksStatus.PERMISSION)
        try {
            val query = uri.buildUpon().appendQueryParameter("completed", "0")
                .appendQueryParameter("sort", "due").appendQueryParameter("limit", "500").build()
            val fields = arrayOf("_id", "title", "is_read_only", "completed_at", "due_date", "modified_at")
            val cursor = try { context.contentResolver.query(query, fields + arrayOf("tag_ids", "priority"), null, null, null) }
                catch (_: IllegalArgumentException) {
                    try { context.contentResolver.query(query, fields + "tag_ids", null, null, null) }
                    catch (_: IllegalArgumentException) { context.contentResolver.query(query, fields, null, null, null) }
                }
                ?: return@withContext TasksSnapshot(TasksStatus.ERROR)
            cursor.use {
                val id = it.getColumnIndexOrThrow("_id")
                val title = it.getColumnIndexOrThrow("title")
                val readOnly = it.getColumnIndexOrThrow("is_read_only")
                val due = it.getColumnIndexOrThrow("due_date")
                val modified = it.getColumnIndexOrThrow("modified_at")
                val tagIds = it.getColumnIndex("tag_ids")
                val priority = it.getColumnIndex("priority")
                val tags = try {
                    val tagsUri = Uri.parse("content://org.tasks.api/v0/tags").buildUpon().appendQueryParameter("limit", "10000").build()
                    val tagCursor = try { context.contentResolver.query(tagsUri, arrayOf("_id", "name", "color"), null, null, null) }
                        catch (_: IllegalArgumentException) { context.contentResolver.query(tagsUri, arrayOf("_id", "name"), null, null, null) }
                    tagCursor?.use { tagsCursor ->
                        val colorIndex = tagsCursor.getColumnIndex("color")
                        buildMap {
                            while (tagsCursor.moveToNext()) {
                                val color = if (colorIndex < 0 || tagsCursor.isNull(colorIndex)) null else tagsCursor.getInt(colorIndex).takeIf { it != 0 }
                                put(tagsCursor.getLong(0).toString(), TaskLabel(tagsCursor.getString(1), color))
                            }
                        }
                    }.orEmpty()
                } catch (_: RuntimeException) { emptyMap() }
                val total = it.extras.getInt(android.content.ContentResolver.EXTRA_TOTAL_COUNT, it.count)
                val tasks = buildList {
                    while (it.moveToNext()) add(Task(it.getLong(id).toString(), it.getString(title), writable = it.getInt(readOnly) == 0 && canWrite(context), due = it.getLong(due), modified = it.getLong(modified), labels = if (tagIds < 0) emptyList() else it.getString(tagIds).orEmpty().split(",").mapNotNull { id -> tags[id] }, priority = if (priority < 0) "none" else it.getString(priority).orEmpty().ifBlank { "none" }))
                }
                TasksSnapshot(TasksStatus.READY, tasks, total)
            }
        } catch (_: SecurityException) { TasksSnapshot(TasksStatus.PERMISSION) }
        catch (_: RuntimeException) { TasksSnapshot(TasksStatus.ERROR) }
    }

    // Reject stale widget actions by comparing the due date, modification timestamp and
    // completion state immediately before the write. Never retry an ambiguous write.
    enum class CompletionResult { SUCCESS, REJECTED, UNKNOWN }

    suspend fun complete(context: Context, id: Long, expectedDue: Long, expectedModified: Long): Boolean =
        completeResult(context, id, expectedDue, expectedModified) == CompletionResult.SUCCESS

    suspend fun completeResult(context: Context, id: Long, expectedDue: Long, expectedModified: Long): CompletionResult = withContext(Dispatchers.IO) {
        if (!canRead(context) || !canWrite(context)) return@withContext CompletionResult.REJECTED
        val item = android.content.ContentUris.withAppendedId(uri, id)
        val valid = try {
            context.contentResolver.query(item, arrayOf("completed_at", "due_date", "is_read_only", "modified_at"), null, null, null)?.use {
                it.moveToFirst() && it.getLong(0) == 0L && it.getLong(1) == expectedDue && it.getInt(2) == 0 && it.getLong(3) == expectedModified
            } ?: false
        } catch (_: RuntimeException) { false }
        if (!valid) return@withContext CompletionResult.REJECTED
        try {
            if (context.contentResolver.update(item, ContentValues().apply { put("completed_at", System.currentTimeMillis()) }, null, null) == 1)
                CompletionResult.SUCCESS else CompletionResult.UNKNOWN
        } catch (_: RuntimeException) { CompletionResult.UNKNOWN }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun observe(context: Context) = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(false) }
        }
        var registered = false
        if (available(context) && canRead(context)) try {
            context.contentResolver.registerContentObserver(Uri.parse("content://org.tasks.api/v0"), true, observer)
            registered = true
        } catch (_: RuntimeException) { }
        trySend(true)
        awaitClose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }.conflate().debounce { if (it) 0 else 150 }.mapLatest { load(context) }.flowOn(Dispatchers.IO)
}

data class TasksSnapshot(val status: TasksStatus, val tasks: List<Task> = emptyList(), val total: Int = 0)
