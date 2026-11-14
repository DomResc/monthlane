package org.monthlane.weather

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.monthlane.data.weather.BreezyWeatherRepository
import org.monthlane.data.weather.WeatherLocation
import org.monthlane.data.cache.DashboardCache
import org.monthlane.data.weather.WeatherSnapshot
import org.monthlane.domain.model.WeatherStatus
import org.json.JSONObject
import org.monthlane.settings.SettingsActivity
import org.monthlane.settings.WeatherControls
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class BreezyWeatherTest {
    private val location = WeatherLocation("1&2&test", "1.0|2.0", "Test city", "UTC")
    private fun compressed(text: String): ByteArray = ByteArrayOutputStream().also { buffer ->
        GZIPOutputStream(buffer).use { it.write(text.toByteArray()) }
    }.toByteArray()
    private fun data(unit: String = "c", dailyUnit: String = unit): String {
        val date = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return """{"refreshTime":1234,"current":{"weatherText":"Rain","weatherCode":"rain","temperature":{"temperature":{"value":12.3,"unit":"$unit"}}},
            "daily":[{"date":$date,"day":{"temperature":{"temperature":{"value":16,"unit":"$dailyUnit"}}},"night":{"temperature":{"temperature":{"value":7,"unit":"$dailyUnit"}}}}],
            "sources":{"forecast":{"text":"Test source (CC BY 4.0)","links":{"License":"https://example.org/license","unsafe":"javascript:alert(1)"}}},"futureField":true}"""
    }
    @Test fun readsInstalledBreezyWhenExplicitlyConfigured() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(BreezyWeatherRepository.canRead(context) && BreezyWeatherRepository.selected(context) != null)
        val snapshot = BreezyWeatherRepository.load(context)
        assertEquals(org.monthlane.domain.model.WeatherStatus.READY, snapshot.status)
        val weather = requireNotNull(snapshot.weather)
        assertTrue(weather.temperature.isFinite())
        assertTrue(weather.unit in setOf("c", "f", "k"))
        assertFalse(weather.sample)
        assertTrue(snapshot.locations.any { it.key == snapshot.selected })
        assertTrue(weather.credits.isNotEmpty())
    }
    @Test fun readsCompressedCurrentDailyAndAttribution() {
        val weather = requireNotNull(BreezyWeatherRepository.decode(compressed(data()), location))
        assertEquals("Test city", weather.city)
        assertEquals(12.3, weather.temperature, 0.001)
        assertEquals(16.0, weather.maximum!!, 0.001)
        assertEquals(7.0, weather.minimum!!, 0.001)
        assertEquals("rain", weather.code)
        assertEquals(1234L, weather.updatedAt)
        assertEquals(listOf("Test source (CC BY 4.0)"), weather.credits)
        assertEquals(mapOf("License" to "https://example.org/license"), weather.creditLinks)
        assertFalse(weather.sample)
    }
    @Test fun cachesAttributionAndReadsLegacyWeatherSnapshots() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(BreezyWeatherRepository.canRead(context) &&
            context.packageManager.resolveContentProvider(BreezyWeatherRepository.AUTHORITY, 0) != null)
        val prefs = context.getSharedPreferences("dashboard_cache", 0)
        val previousCache = prefs.getString("weather", null)
        val previousLocation = BreezyWeatherRepository.selected(context)
        val weather = requireNotNull(BreezyWeatherRepository.decode(compressed(data()), location))
        try {
            BreezyWeatherRepository.select(context, location.key)
            DashboardCache.saveWeather(context, WeatherSnapshot(WeatherStatus.READY, selected = location.key, weather = weather))
            val cached = requireNotNull(DashboardCache.weather(context)?.weather)
            assertEquals(weather, cached)
            val json = JSONObject(prefs.getString("weather", null)!!)
            json.getJSONObject("creditLinks").put("unsafe", "javascript:alert(1)")
            assertTrue(prefs.edit().putString("weather", json.toString()).commit())
            assertEquals(weather.creditLinks, DashboardCache.weather(context)!!.weather!!.creditLinks)
            json.remove("credits")
            json.remove("creditLinks")
            assertTrue(prefs.edit().putString("weather", json.toString()).commit())
            assertEquals(weather.copy(credits = emptyList(), creditLinks = emptyMap()), DashboardCache.weather(context)?.weather)
        } finally {
            BreezyWeatherRepository.select(context, previousLocation)
            assertTrue(prefs.edit().apply {
                if (previousCache == null) remove("weather") else putString("weather", previousCache)
            }.commit())
        }
    }
    @Test fun showsFullAttributionAndOpensSourceLinksInWeatherSettings() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val weather = requireNotNull(BreezyWeatherRepository.decode(compressed(data()), location))
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        val opened = java.util.concurrent.atomic.AtomicReference<Intent>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW) {
                    opened.set(Intent(intent))
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme { WeatherControls(WeatherSnapshot(WeatherStatus.READY, weather = weather), onRefresh = {}) }
                }
            }
            fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
                node ?: return null
                if (node.text?.toString() == text) return node
                for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
                return null
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            var credit: AccessibilityNodeInfo? = null
            var link: AccessibilityNodeInfo? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                credit = find(root, weather.credits.single())
                link = find(root, "License")
                if (credit != null && link != null) break
                SystemClock.sleep(100)
            }
            assertNotNull("Full source credit is absent", credit)
            assertNotNull("Source link is absent", link)
            var button = requireNotNull(link)
            while (!button.isClickable && button.parent != null) button = button.parent
            assertTrue("Source link is not clickable", button.isClickable)
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val launchDeadline = SystemClock.elapsedRealtime() + 5000
            while (opened.get() == null && SystemClock.elapsedRealtime() < launchDeadline) SystemClock.sleep(50)
            assertEquals("https://example.org/license", opened.get()?.dataString)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun preservesFahrenheitAndOmitsDailyValuesWithDifferentUnits() {
        val weather = requireNotNull(BreezyWeatherRepository.decode(compressed(data("f", "c")), location))
        assertEquals("f", weather.unit)
        assertNull(weather.minimum)
        assertNull(weather.maximum)
    }
    @Test fun rejectsUnsupportedUnitsAndMissingCurrentReadings() {
        assertNull(BreezyWeatherRepository.decode(compressed(data("unknown")), location))
        assertNull(BreezyWeatherRepository.decode(compressed("{}"), location))
        assertNull(BreezyWeatherRepository.decode(compressed("null"), location))
    }
    @Test fun malformedGzipCannotBecomeSampleWeather() {
        assertTrue(runCatching { BreezyWeatherRepository.decode(byteArrayOf(1, 2, 3), location) }.isFailure)
    }
    @Test fun rejectsOversizedDecompressedPayload() {
        assertTrue(runCatching { BreezyWeatherRepository.decode(compressed(" ".repeat(4 * 1024 * 1024 + 1)), location) }.isFailure)
    }
}
