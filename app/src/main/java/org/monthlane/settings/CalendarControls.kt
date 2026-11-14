package org.monthlane.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import org.monthlane.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.monthlane.data.calendar.CalendarSettings
import org.monthlane.data.calendar.CalendarSnapshot
import org.monthlane.domain.model.CalendarStatus

@Composable
fun CalendarControls(snapshot: CalendarSnapshot, onPermissionResult: () -> Unit, onSelection: (Set<Long>?) -> Unit, onRefresh: () -> Unit, selectionRevision: Int) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onPermissionResult() }
    val selected = remember(snapshot.calendars, selectionRevision) {
        CalendarSettings.selectedIds(context) ?: snapshot.calendars.filter { it.visible }.map { it.id }.toSet()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (snapshot.status == CalendarStatus.PERMISSION_REQUIRED) {
            Text(stringResource(R.string.calendar_permission_intro), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { permission.launch(Manifest.permission.READ_CALENDAR) }) { Text(stringResource(R.string.connect_calendar)) }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text(stringResource(R.string.app_permissions)) }
        } else {
            when (snapshot.status) {
                CalendarStatus.LOADING -> Text(stringResource(R.string.loading_calendars))
                CalendarStatus.NO_CALENDARS -> Text(stringResource(R.string.no_calendars_help))
                CalendarStatus.ERROR -> Text(stringResource(R.string.calendar_error_help))
                else -> Unit
            }
            snapshot.calendars.forEach { calendar ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(calendar.id in selected, onCheckedChange = { checked -> onSelection(if (checked) selected + calendar.id else selected - calendar.id) })
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                        if (calendar.accountName.isNotBlank()) Text(calendar.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh_calendar)) }
        }
    }
}
