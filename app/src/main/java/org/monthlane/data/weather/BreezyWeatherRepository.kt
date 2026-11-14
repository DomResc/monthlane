package org.monthlane.data.weather

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.monthlane.domain.model.Weather
import org.monthlane.domain.model.WeatherStatus
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream

// Independent client of the public Breezy 6.2.2 provider protocol; no upstream code is bundled.
data class WeatherLocation(val id: String, val key: String, val name: String, val zone: String)
data class WeatherSnapshot(val status: WeatherStatus, val locations: List<WeatherLocation> = emptyList(), val selected: String? = null, val weather: Weather? = null)

object BreezyWeatherRepository {
    const val PACKAGE = "org.breezyweather"
    const val PERMISSION = "org.breezyweather.READ_PROVIDER"
    const val AUTHORITY = "$PACKAGE.provider.weather"
    private const val PREFS = "weather"
    private const val LOCATION = "location"
    fun selected(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LOCATION, null)
    fun select(context: Context, key: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (key == null) remove(LOCATION) else putString(LOCATION, key)
        }
    }
    fun canRead(context: Context) = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    private fun uri(path: String) = "content://$AUTHORITY/$path".toUri()

    suspend fun load(context: Context): WeatherSnapshot = withContext(Dispatchers.IO) {
        if (context.packageManager.resolveContentProvider(AUTHORITY, 0) == null) return@withContext WeatherSnapshot(WeatherStatus.MISSING_APP)
        if (!canRead(context)) return@withContext WeatherSnapshot(WeatherStatus.PERMISSION_REQUIRED)
        try {
            val resolver = context.contentResolver
            resolver.query(uri("version"), null, null, null, null).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return@withContext WeatherSnapshot(WeatherStatus.ERROR)
                if (cursor.getInt(cursor.getColumnIndexOrThrow("major")) != 0) return@withContext WeatherSnapshot(WeatherStatus.INCOMPATIBLE)
            }
            val locations = buildList {
                resolver.query(uri("locations").buildUpon().appendQueryParameter("limit", "50").build(), null, null, null, null).use { cursor ->
                    checkNotNull(cursor)
                    while (cursor.moveToNext()) {
                        fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column)).orEmpty()
                        val current = cursor.getInt(cursor.getColumnIndexOrThrow("is_current_position")) != 0
                        val key = if (current) "current" else "${cursor.getDouble(cursor.getColumnIndexOrThrow("latitude"))}|${cursor.getDouble(cursor.getColumnIndexOrThrow("longitude"))}"
                        add(WeatherLocation(text("id"), key, listOf(text("custom_name"), text("city"), text("district"), text("admin1"), text("country")).firstOrNull { it.isNotBlank() } ?: "—", text("timezone")))
                    }
                }
            }
            if (locations.isEmpty()) return@withContext WeatherSnapshot(WeatherStatus.NO_LOCATIONS)
            val selected = selected(context)
            val location = locations.firstOrNull { it.key == selected }
                ?: return@withContext WeatherSnapshot(WeatherStatus.CHOOSE_LOCATION, locations)
            val weatherUri = uri("weather").buildUpon().apply {
                for (key in listOf("withHourly", "withMinutely", "withAlerts", "withNormals")) appendQueryParameter(key, "false")
                appendQueryParameter("withDaily", "true")
            }.build()
            val weather = resolver.query(weatherUri, null, "id=${location.id}", null, null).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) null else {
                    val column = cursor.getColumnIndexOrThrow("weather")
                    if (cursor.isNull(column)) null else decode(cursor.getBlob(column), location)
                }
            }
            WeatherSnapshot(if (weather == null) WeatherStatus.NO_DATA else WeatherStatus.READY, locations, location.key, weather)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { WeatherSnapshot(WeatherStatus.PERMISSION_REQUIRED) }
        catch (_: Exception) { WeatherSnapshot(WeatherStatus.ERROR) }
    }

    /** Bounded gzip/JSON decoding tolerates optional and future minor-version fields. */
    fun decode(blob: ByteArray, location: WeatherLocation): Weather? {
        require(blob.size <= 2 * 1024 * 1024)
        val bytes = GZIPInputStream(ByteArrayInputStream(blob)).use { stream ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(result.size() + count <= 4 * 1024 * 1024)
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        val payload = bytes.toString(Charsets.UTF_8)
        if (payload.trim() == "null") return null
        val root = JSONObject(payload)
        val current = root.optJSONObject("current") ?: return null
        fun temperature(parent: JSONObject?): Pair<Double, String>? {
            val unit = parent?.optJSONObject("temperature")?.optJSONObject("temperature") ?: return null
            val number = unit.optDouble("value", Double.NaN)
            val code = unit.optString("unit")
            return if (number.isFinite() && code in setOf("c", "f", "k")) number to code else null
        }
        val reading = temperature(current) ?: return null
        val zone = runCatching { ZoneId.of(location.zone) }.getOrDefault(ZoneId.systemDefault())
        val today = LocalDate.now(zone)
        val daily = root.optJSONArray("daily")
        val day = (0 until (daily?.length() ?: 0)).mapNotNull { daily?.optJSONObject(it) }.firstOrNull {
            val time = it.optLong("date", -1)
            time > 0 && Instant.ofEpochMilli(time).atZone(zone).toLocalDate() == today
        }
        val high = temperature(day?.optJSONObject("day"))?.takeIf { it.second == reading.second }?.first
        val low = temperature(day?.optJSONObject("night"))?.takeIf { it.second == reading.second }?.first
        val sources = root.optJSONObject("sources")
        val credits = sources?.keys()?.asSequence()?.mapNotNull { key -> sources.optJSONObject(key)?.optString("text")?.takeIf { it.isNotBlank() && it != "null" } }?.distinct()?.toList().orEmpty()
        val links = buildMap<String, String> {
            sources?.keys()?.forEach { key ->
                val items = sources.optJSONObject(key)?.optJSONObject("links")
                items?.keys()?.forEach { label ->
                    val url = items.optString(label)
                    if (url.startsWith("https://") || url.startsWith("http://")) put(label, url)
                }
            }
        }
        return Weather(location.name, reading.first, low, high, current.optString("weatherText").takeUnless { it.isBlank() || it == "null" }.orEmpty(),
            current.optString("weatherCode").takeUnless { it == "null" }.orEmpty(), reading.second,
            root.optLong("refreshTime", -1).takeIf { it > 0 }, credits, links)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(context: Context) = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        var registered = false
        try {
            if (canRead(context)) {
                context.contentResolver.registerContentObserver(uri(""), true, observer)
                registered = true
            }
        } catch (_: SecurityException) { /* load reports a concurrent permission change. */ }
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose {
            if (registered) context.contentResolver.unregisterContentObserver(observer)
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.conflate().mapLatest { load(context) }
}
