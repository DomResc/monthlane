package org.monthlane.tasks

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.monthlane.data.tasks.TasksRepository

@RunWith(AndroidJUnit4::class)
class TasksShortcutTest {
    @Test fun installedTasksAppHandlesBothShortcuts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = context.packageManager.resolveContentProvider("org.tasks.api", 0)
        assumeTrue("Tasks.org provider is not installed", provider != null)
        val packageName = requireNotNull(provider).packageName
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("Tasks.org has no launch activity", launch)
        val create = Intent(Intent.ACTION_VIEW).setDataAndType(TasksRepository.uri, "vnd.android.cursor.dir/vnd.org.tasks.task").setPackage(packageName)
        assertNotNull("Tasks.org cannot open its task editor", create.resolveActivity(context.packageManager))
    }
}
