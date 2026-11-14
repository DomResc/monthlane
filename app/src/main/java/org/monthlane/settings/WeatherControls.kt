package org.monthlane.settings

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monthlane.R
import org.monthlane.data.weather.BreezyWeatherRepository
import org.monthlane.data.weather.WeatherSnapshot
import org.monthlane.domain.model.WeatherStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun WeatherStatus.messageResource(): Int = when (this) {
    WeatherStatus.LOADING -> R.string.weather_loading
    WeatherStatus.MISSING_APP -> R.string.weather_install
    WeatherStatus.PERMISSION_REQUIRED -> R.string.weather_connect
    WeatherStatus.INCOMPATIBLE -> R.string.weather_incompatible
    WeatherStatus.NO_LOCATIONS -> R.string.weather_no_locations
    WeatherStatus.CHOOSE_LOCATION -> R.string.weather_choose
    WeatherStatus.NO_DATA -> R.string.weather_no_data
    else -> R.string.weather_error
}

fun updatedLabel(context: Context, millis: Long?): String = if (millis == null) context.getString(R.string.weather_time_unknown) else {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(context.resources.configuration.locales[0]).withZone(ZoneId.systemDefault())
    context.getString(R.string.weather_updated, formatter.format(Instant.ofEpochMilli(millis)))
}

@Composable
fun WeatherControls(snapshot: WeatherSnapshot, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefresh() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (snapshot.status == WeatherStatus.PERMISSION_REQUIRED) {
            Button(onClick = { permission.launch(BreezyWeatherRepository.PERMISSION) }) { Text(stringResource(R.string.weather_connect)) }
            TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri())) }) { Text(stringResource(R.string.weather_permissions)) }
        } else if (snapshot.status != WeatherStatus.READY) Text(stringResource(snapshot.status.messageResource()))
        if (snapshot.locations.isNotEmpty()) {
            snapshot.locations.forEach { location ->
                Row(Modifier.fillMaxWidth().selectable(location.key == snapshot.selected, role = Role.RadioButton, onClick = { BreezyWeatherRepository.select(context, location.key); onRefresh() }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(location.key == snapshot.selected, null)
                    Text(location.name, Modifier.weight(1f).padding(start = 8.dp))
                }
            }
        }
        snapshot.weather?.let { weather ->
            Text(updatedLabel(context, weather.updatedAt), style = MaterialTheme.typography.bodySmall)
            if (weather.stale()) Text(stringResource(R.string.weather_stale), color = MaterialTheme.colorScheme.error)
            if (weather.credits.isNotEmpty() || weather.creditLinks.isNotEmpty()) {
                Text(stringResource(R.string.weather_sources), style = MaterialTheme.typography.titleSmall)
                weather.credits.forEach { credit -> Text(credit, style = MaterialTheme.typography.bodySmall) }
                weather.creditLinks.forEach { (label, url) ->
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, R.string.weather_source_open_error, Toast.LENGTH_LONG).show()
                            } catch (_: SecurityException) {
                                Toast.makeText(context, R.string.weather_source_open_error, Toast.LENGTH_LONG).show()
                            }
                        }) { Text(label) }
                    }
                }
            }
        }
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.weather_refresh)) }
    }
}
