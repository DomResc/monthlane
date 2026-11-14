package org.monthlane.tasks

import android.content.ContentUris
import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.monthlane.data.tasks.TasksRepository
import org.monthlane.domain.model.TasksStatus
import org.monthlane.sync.PendingTasks
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class TasksProviderTest {
    @Test fun readsAndCachesTagColors() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val tagsUri = android.net.Uri.parse("content://org.tasks.api/v0/tags")
        val linksUri = android.net.Uri.parse("content://org.tasks.api/v0/task_tags")
        val tag = requireNotNull(resolver.insert(tagsUri, ContentValues().apply {
            put("name", "Monthlane color QA ${System.nanoTime()}")
            put("color", 0xFF4CAF50.toInt())
        }))
        val item = requireNotNull(resolver.insert(TasksRepository.uri, ContentValues().apply { put("title", "Monthlane color QA"); put("priority", "low") }))
        val prefs = context.getSharedPreferences("dashboard_cache", 0)
        val previous = prefs.getString("tasks", null)
        try {
            requireNotNull(resolver.insert(linksUri, ContentValues().apply {
                put("task_id", ContentUris.parseId(item))
                put("tag_id", ContentUris.parseId(tag))
            }))
            val snapshot = TasksRepository.load(context)
            val label = snapshot.tasks.single { it.id == ContentUris.parseId(item).toString() }.labels.single()
            assertEquals("low", snapshot.tasks.single { it.id == ContentUris.parseId(item).toString() }.priority)
            assertEquals(0xFF4CAF50.toInt(), label.color)
            org.monthlane.data.cache.DashboardCache.saveTasks(context, snapshot)
            assertEquals(label, org.monthlane.data.cache.DashboardCache.tasks(context)!!.tasks.single { it.id == ContentUris.parseId(item).toString() }.labels.single())
            assertEquals("low", org.monthlane.data.cache.DashboardCache.tasks(context)!!.tasks.single { it.id == ContentUris.parseId(item).toString() }.priority)
            val oldCache = JSONObject(prefs.getString("tasks", null)!!)
            val items = oldCache.getJSONArray("items")
            for (index in 0 until items.length()) {
                val cachedTask = items.getJSONObject(index)
                if (cachedTask.getString("id") == ContentUris.parseId(item).toString())
                    cachedTask.put("labels", org.json.JSONArray().put(label.name))
            }
            assertTrue(prefs.edit().putString("tasks", oldCache.toString()).commit())
            val oldLabel = org.monthlane.data.cache.DashboardCache.tasks(context)!!.tasks.single { it.id == ContentUris.parseId(item).toString() }.labels.single()
            assertEquals(label.name, oldLabel.name)
            assertNull(oldLabel.color)
        } finally {
            prefs.edit().apply { if (previous == null) remove("tasks") else putString("tasks", previous) }.commit()
            resolver.delete(item, null, null)
            resolver.delete(tag, null, null)
        }
    }
    @Test fun restoresPendingCompletionWithoutScheduledWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val item = requireNotNull(resolver.insert(TasksRepository.uri, ContentValues().apply { put("title", "Monthlane recovery QA") }))
        val id = ContentUris.parseId(item)
        val prefs = context.getSharedPreferences("pending_tasks", 0)
        try {
            val task = TasksRepository.load(context).tasks.single { it.id == id.toString() }
            val pending = JSONObject().put("token", "recovery-test").put("due", task.due).put("modified", task.modified)
            assertTrue(prefs.edit().putString(task.id, pending.toString()).commit())
            PendingTasks.recover(context)
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (!PendingTasks.committed(context, task) && android.os.SystemClock.elapsedRealtime() < deadline) delay(100)
            assertFalse("Pending completion was not recovered", PendingTasks.contains(context, task.id))
            assertTrue(PendingTasks.committed(context, task))
            assertFalse(TasksRepository.load(context).tasks.any { it.id == task.id })
        } finally {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("complete-task-$id")
            prefs.edit().remove(id.toString()).remove("attempt:$id").remove("display:$id").commit()
            resolver.delete(item, null, null)
        }
    }
    @Test fun readsAndCompletesRealTasksWithoutRepeatingAStaleAction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        assertTrue(TasksRepository.available(context))
        assertTrue(TasksRepository.canRead(context))
        assertTrue(TasksRepository.canWrite(context))
        val item = requireNotNull(resolver.insert(TasksRepository.uri, ContentValues().apply { put("title", "Monthlane QA task") }))
        try {
            val id = ContentUris.parseId(item)
            val snapshot = TasksRepository.load(context)
            assertEquals(TasksStatus.READY, snapshot.status)
            val task = snapshot.tasks.single { it.id == id.toString() }
            assertTrue(TasksRepository.complete(context, id, task.due, task.modified))
            assertFalse(TasksRepository.complete(context, id, task.due, task.modified))
            assertFalse(TasksRepository.load(context).tasks.any { it.id == id.toString() })
        } finally { resolver.delete(item, null, null) }
    }
    @Test fun pendingCompletionCanBeCancelledAndCannotBeReplayed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        assertTrue(TasksRepository.canRead(context) && TasksRepository.canWrite(context))
        val item = requireNotNull(resolver.insert(TasksRepository.uri, ContentValues().apply { put("title", "Monthlane undo QA") }))
        try {
            val id = ContentUris.parseId(item)
            val task = TasksRepository.load(context).tasks.single { it.id == id.toString() }
            PendingTasks.toggle(context, id, task.due, task.modified)
            assertTrue(PendingTasks.contains(context, task.id))
            assertFalse(PendingTasks.committed(context, task))
            val token = JSONObject(context.getSharedPreferences("pending_tasks", 0).getString(task.id, null)!!).getString("token")
            PendingTasks.toggle(context, id, task.due, task.modified + 1)
            assertTrue(PendingTasks.contains(context, task.id))
            PendingTasks.toggle(context, id, task.due, task.modified)
            assertFalse(PendingTasks.contains(context, task.id))
            assertFalse(PendingTasks.committed(context, task))
            assertNull(PendingTasks.complete(context, id, token))
            assertTrue(TasksRepository.load(context).tasks.any { it.id == task.id })
            PendingTasks.toggle(context, id, task.due, task.modified)
            val nextToken = JSONObject(context.getSharedPreferences("pending_tasks", 0).getString(task.id, null)!!).getString("token")
            assertEquals(true, PendingTasks.complete(context, id, nextToken))
            assertFalse(PendingTasks.contains(context, task.id))
            assertTrue(PendingTasks.committed(context, task))
            assertFalse(PendingTasks.committed(context, task.copy(modified = task.modified + 1)))
            assertNull(PendingTasks.complete(context, id, nextToken))
            assertFalse(TasksRepository.load(context).tasks.any { it.id == task.id })
        } finally { resolver.delete(item, null, null) }
    }

    @Test fun rejectedStaleCompletionRestoresTheTask() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val item = requireNotNull(resolver.insert(TasksRepository.uri, ContentValues().apply { put("title", "Monthlane stale QA") }))
        try {
            val id = ContentUris.parseId(item)
            val task = TasksRepository.load(context).tasks.single { it.id == id.toString() }
            PendingTasks.toggle(context, id, task.due, task.modified + 1)
            val token = JSONObject(context.getSharedPreferences("pending_tasks", 0).getString(task.id, null)!!).getString("token")
            assertEquals(false, PendingTasks.complete(context, id, token))
            assertFalse(PendingTasks.contains(context, task.id))
            assertFalse(PendingTasks.committed(context, task))
            assertTrue(TasksRepository.load(context).tasks.any { it.id == task.id })
        } finally { resolver.delete(item, null, null) }
    }

    @Test fun interruptedWriteIsVisibleButCannotBeRepeated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val item = requireNotNull(resolver.insert(TasksRepository.uri, ContentValues().apply { put("title", "Monthlane interrupted QA") }))
        val id = ContentUris.parseId(item)
        val prefs = context.getSharedPreferences("pending_tasks", 0)
        try {
            val task = TasksRepository.load(context).tasks.single { it.id == id.toString() }
            val value = JSONObject().put("token", "old").put("due", task.due).put("modified", task.modified).put("owner", "previous-process")
            assertTrue(prefs.edit().putString("attempt:$id", value.toString()).putString("display:$id", value.toString()).commit())
            assertTrue(PendingTasks.uncertain(context, task))
            assertFalse(PendingTasks.displayed(context, task))
            assertFalse(PendingTasks.committed(context, task))
            assertFalse(PendingTasks.uncertain(context, task.copy(modified = task.modified + 1)))
            assertTrue(prefs.edit().remove("display:$id").commit())
            PendingTasks.toggle(context, id, task.due, task.modified)
            assertFalse(PendingTasks.contains(context, task.id))
        } finally {
            prefs.edit().remove("attempt:$id").remove("display:$id").commit()
            resolver.delete(item, null, null)
        }
    }

    @Test fun recurringTaskAdvancesOnlyOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val item = requireNotNull(resolver.insert(TasksRepository.uri, ContentValues().apply {
            put("title", "Monthlane recurrence QA")
            put("due_date", System.currentTimeMillis() + 86400000L)
            put("recurrence", "FREQ=DAILY;COUNT=3")
        }))
        try {
            val id = ContentUris.parseId(item)
            val original = TasksRepository.load(context).tasks.single { it.id == id.toString() }
            PendingTasks.toggle(context, id, original.due, original.modified)
            val token = JSONObject(context.getSharedPreferences("pending_tasks", 0).getString(original.id, null)!!).getString("token")
            assertEquals(true, PendingTasks.complete(context, id, token))
            val next = TasksRepository.load(context).tasks.single { it.id == original.id }
            assertTrue(next.due > original.due)
            assertFalse(PendingTasks.committed(context, next))
            assertNull(PendingTasks.complete(context, id, token))
            assertEquals(next.due, TasksRepository.load(context).tasks.single { it.id == original.id }.due)
        } finally { resolver.delete(item, null, null) }
    }

}
