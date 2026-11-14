package org.monthlane.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.monthlane.data.weather.BreezyWeatherRepository

/** Keep the external app launch user-initiated from the widget PendingIntent. */
class WeatherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = packageManager.getLaunchIntentForPackage(BreezyWeatherRepository.PACKAGE)
        try {
            startActivity(launch ?: Intent(this, SettingsActivity::class.java).putExtra("weather_open_error", true))
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("weather_open_error", true))
        } catch (_: SecurityException) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("weather_open_error", true))
        }
        finish()
    }
}
