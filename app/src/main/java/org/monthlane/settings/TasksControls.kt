package org.monthlane.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monthlane.R
import org.monthlane.data.tasks.*
import org.monthlane.domain.model.TasksStatus

@Composable
fun TasksControls(snapshot: TasksSnapshot, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onRefresh() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (snapshot.status) {
            TasksStatus.LOADING -> Text(stringResource(R.string.tasks_loading))
            TasksStatus.MISSING -> Text(stringResource(R.string.tasks_missing))
            TasksStatus.ERROR -> Text(stringResource(R.string.tasks_error))
            TasksStatus.PERMISSION -> Text(stringResource(R.string.tasks_connect))
            TasksStatus.READY -> Text(stringResource(R.string.tasks_count, snapshot.total))
        }
        if (snapshot.status != TasksStatus.MISSING && (!TasksRepository.canRead(context) || !TasksRepository.canWrite(context))) {
            Button(onClick = { permissions.launch(arrayOf(TasksRepository.READ, TasksRepository.WRITE)) }) { Text(stringResource(R.string.tasks_connect)) }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text(stringResource(R.string.app_permissions)) }
        }
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.tasks_refresh)) }
    }
}
