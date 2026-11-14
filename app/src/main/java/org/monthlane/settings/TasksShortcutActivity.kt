package org.monthlane.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.monthlane.data.tasks.TasksRepository

/** Opens Tasks.org or its task creation screen from a user-initiated tap. */
class TasksShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val provider = packageManager.resolveContentProvider("org.tasks.api", 0)
        val target = provider?.packageName?.let { packageName ->
            if (intent.data?.host == "task") Intent(Intent.ACTION_VIEW).setDataAndType(android.content.ContentUris.withAppendedId(TasksRepository.uri, intent.data!!.lastPathSegment!!.toLong()), "vnd.android.cursor.item/vnd.org.tasks.task").setPackage(packageName)
            else if (intent.data?.host == CREATE) Intent(Intent.ACTION_VIEW).setDataAndType(TasksRepository.uri, "vnd.android.cursor.dir/vnd.org.tasks.task").setPackage(packageName)
            else packageManager.getLaunchIntentForPackage(packageName)
        }
        try {
            if (target == null) unavailable() else startActivity(target)
        } catch (_: ActivityNotFoundException) {
            unavailable()
        } catch (_: SecurityException) {
            unavailable()
        }
        finish()
    }

    private fun unavailable() {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("tasks_open_error", true))
    }

    companion object {
        fun taskIntent(context: Context, id: String) = Intent(context, TasksShortcutActivity::class.java)
            .setData(Uri.Builder().scheme("monthlane").authority("task").appendPath(id).build())
        private const val CREATE = "create"
        fun intent(context: Context, create: Boolean) = Intent(context, TasksShortcutActivity::class.java)
            .setData(Uri.Builder().scheme("monthlane").authority(if (create) CREATE else "tasks").build())
    }
}
