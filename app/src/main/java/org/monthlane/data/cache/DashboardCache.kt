package org.monthlane.data.cache

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.monthlane.data.calendar.CalendarSettings
import org.monthlane.data.calendar.CalendarSnapshot
import org.monthlane.data.tasks.TasksRepository
import org.monthlane.data.tasks.TasksSnapshot
import org.monthlane.data.weather.BreezyWeatherRepository
import org.monthlane.data.weather.WeatherSnapshot
import org.monthlane.domain.model.*
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Last successful provider snapshots, scoped to the permission and selection that produced them. */
object DashboardCache {
    private fun prefs(context: Context) = context.getSharedPreferences("dashboard_cache", Context.MODE_PRIVATE)
    private fun key(context: Context, month: YearMonth): String = listOf(month, ZoneId.systemDefault(),
        CalendarSettings.selectedIds(context)?.sorted()?.joinToString(",") ?: "default", context.resources.configuration.locales.toLanguageTags()).joinToString("|")
    private fun array(json: JSONObject, name: String): List<JSONObject> {
        val a = json.getJSONArray(name)
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun saveCalendar(context: Context, month: YearMonth, snapshot: CalendarSnapshot) {
        if (snapshot.status != CalendarStatus.READY) return
        val events = JSONArray()
        snapshot.events.forEach { e -> events.put(JSONObject().put("id", e.id).put("calendar", e.calendarId).put("title", e.title)
            .put("start", e.start.toEpochMilli()).put("end", e.end.toEpochMilli()).put("allDay", e.allDay)
            .put("description", e.description).put("location", e.location).put("color", e.color ?: JSONObject.NULL)
            .put("category", e.category).put("provider", e.providerId ?: JSONObject.NULL)) }
        prefs(context).edit().putString("calendar", JSONObject().put("key", key(context, month)).put("events", events).toString()).apply()
    }
    fun calendar(context: Context, month: YearMonth): CalendarSnapshot? {
        if (!CalendarSettings.canRead(context)) return null
        return runCatching {
            val json = JSONObject(prefs(context).getString("calendar", null) ?: return null)
            if (json.getString("key") != key(context, month)) return null
            CalendarSnapshot(CalendarStatus.READY, events = array(json, "events").map { e ->
                CalendarEvent(e.getString("id"), e.getLong("calendar"), e.getString("title"), Instant.ofEpochMilli(e.getLong("start")),
                    Instant.ofEpochMilli(e.getLong("end")), e.getBoolean("allDay"), e.getString("description"), e.getString("location"),
                    if (e.isNull("color")) null else e.getInt("color"), e.getInt("category"), if (e.isNull("provider")) null else e.getLong("provider"))
            })
        }.getOrNull()
    }
    fun saveTasks(context: Context, snapshot: TasksSnapshot) {
        if (snapshot.status != TasksStatus.READY) return
        val tasks = JSONArray()
        snapshot.tasks.forEach { t -> tasks.put(JSONObject().put("id", t.id).put("title", t.title).put("writable", t.writable)
            .put("due", t.due).put("modified", t.modified).put("priority", t.priority).put("labels", JSONArray().apply {
                t.labels.forEach { label -> put(JSONObject().put("name", label.name).put("color", label.color ?: JSONObject.NULL)) }
            })) }
        prefs(context).edit().putString("tasks", JSONObject().put("total", snapshot.total).put("items", tasks).toString()).apply()
    }
    fun tasks(context: Context): TasksSnapshot? {
        if (!TasksRepository.available(context) || !TasksRepository.canRead(context)) return null
        return runCatching {
            val json = JSONObject(prefs(context).getString("tasks", null) ?: return null)
            TasksSnapshot(TasksStatus.READY, array(json, "items").map { t ->
                val labels = t.getJSONArray("labels")
                Task(t.getString("id"), t.getString("title"), writable = t.getBoolean("writable") && TasksRepository.canWrite(context),
                    due = t.getLong("due"), modified = t.getLong("modified"), priority = t.optString("priority", "none"), labels = (0 until labels.length()).map { index ->
                        val label = labels.optJSONObject(index)
                        if (label == null) TaskLabel(labels.getString(index))
                        else TaskLabel(label.getString("name"), if (label.isNull("color")) null else label.getInt("color"))
                    })
            }, json.getInt("total"))
        }.getOrNull()
    }
    fun saveWeather(context: Context, snapshot: WeatherSnapshot) {
        val w = snapshot.weather ?: return
        if (snapshot.status != WeatherStatus.READY) return
        val json = JSONObject().put("location", snapshot.selected).put("city", w.city).put("temperature", w.temperature)
            .put("min", w.minimum ?: JSONObject.NULL).put("max", w.maximum ?: JSONObject.NULL).put("condition", w.condition)
            .put("code", w.code).put("unit", w.unit).put("updated", w.updatedAt ?: JSONObject.NULL)
            .put("credits", JSONArray(w.credits)).put("creditLinks", JSONObject(w.creditLinks))
        prefs(context).edit().putString("weather", json.toString()).apply()
    }
    fun weather(context: Context): WeatherSnapshot? {
        if (!BreezyWeatherRepository.canRead(context) || context.packageManager.resolveContentProvider(BreezyWeatherRepository.AUTHORITY, 0) == null) return null
        return runCatching {
            val j = JSONObject(prefs(context).getString("weather", null) ?: return null)
            if (j.optString("location") != BreezyWeatherRepository.selected(context)) return null
            val credits = j.optJSONArray("credits")
            val links = j.optJSONObject("creditLinks")
            val weather = Weather(j.getString("city"), j.getDouble("temperature"), if (j.isNull("min")) null else j.getDouble("min"),
                if (j.isNull("max")) null else j.getDouble("max"), j.getString("condition"), j.getString("code"), j.getString("unit"),
                if (j.isNull("updated")) null else j.getLong("updated"),
                (0 until (credits?.length() ?: 0)).mapNotNull { credits?.optString(it)?.takeIf(String::isNotBlank) },
                buildMap {
                    links?.keys()?.forEach { label ->
                        val url = links.optString(label)
                        if (url.startsWith("https://") || url.startsWith("http://")) put(label, url)
                    }
                })
            WeatherSnapshot(WeatherStatus.READY, selected = j.getString("location"), weather = weather)
        }.getOrNull()
    }
}
