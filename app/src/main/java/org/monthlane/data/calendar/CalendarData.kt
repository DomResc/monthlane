package org.monthlane.data.calendar

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monthlane.data.mock.MockDashboard
import org.monthlane.data.cache.DashboardCache
import org.monthlane.domain.model.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class CalendarSnapshot(
    val status: CalendarStatus,
    val calendars: List<CalendarSource> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
) {
    private val markerCache = java.util.concurrent.ConcurrentHashMap<Pair<YearMonth, ZoneId>, Map<LocalDate, List<CalendarMarker>>>()

    fun dashboard(selectedDate: LocalDate, completed: Set<String> = emptySet(), zone: ZoneId = ZoneId.systemDefault()): DashboardState {
        val month = YearMonth.from(selectedDate)
        return MockDashboard.load(selectedDate, completed = completed).copy(
            events = eventsOnDate(events, selectedDate, zone), indicators = markerCache.computeIfAbsent(month to zone) { calendarMarkers(events, month, zone) },
            calendarStatus = status, zone = zone, weather = null,
        )
    }
}

object CalendarData {
    private data class CacheKey(val month: YearMonth, val zone: ZoneId, val selection: Set<Long>?, val locale: String)
    private data class Cached(val snapshot: CalendarSnapshot, val savedAt: Long)
    private val cache = LinkedHashMap<CacheKey, Cached>()
    private val queryMutex = Mutex()
    private var generation = 0L

    @Synchronized fun invalidate() { generation++; cache.clear() }

    /** Reuse a recent snapshot, including its marker cache, when publishing an interaction. */
    fun cachedMonth(context: Context, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): CalendarSnapshot? {
        if (!CalendarSettings.canRead(context)) return null
        val key = CacheKey(month, zone, CalendarSettings.selectedIds(context), context.resources.configuration.locales.toLanguageTags())
        return synchronized(this) { cache[key]?.takeIf { android.os.SystemClock.elapsedRealtime() - it.savedAt < 60_000 }?.snapshot }
    }

    /** A small, permission-checked memory cache avoids requerying every responsive composition. */
    suspend fun month(context: Context, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): CalendarSnapshot {
        if (!CalendarSettings.canRead(context)) {
            invalidate()
            return CalendarSnapshot(CalendarStatus.PERMISSION_REQUIRED)
        }
        return queryMutex.withLock {
            val key = CacheKey(month, zone, CalendarSettings.selectedIds(context), context.resources.configuration.locales.toLanguageTags())
            val now = android.os.SystemClock.elapsedRealtime()
            val version = synchronized(this) {
                cache[key]?.takeIf { now - it.savedAt < 60_000 }?.let { return@withLock it.snapshot }
                generation
            }
            val snapshot = loadMonth(context, month, zone)
            synchronized(this) {
                if (version == generation && snapshot.status != CalendarStatus.ERROR && snapshot.status != CalendarStatus.PERMISSION_REQUIRED) {
                    if (key.selection == CalendarSettings.selectedIds(context) && key.zone == ZoneId.systemDefault())
                        DashboardCache.saveCalendar(context, month, snapshot)
                    cache[key] = Cached(snapshot, android.os.SystemClock.elapsedRealtime())
                    while (cache.size > 3) cache.remove(cache.keys.first())
                }
            }
            snapshot
        }
    }

    suspend fun loadMonth(context: Context, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): CalendarSnapshot {
        if (!CalendarSettings.canRead(context)) return CalendarSnapshot(CalendarStatus.PERMISSION_REQUIRED)
        return try {
            val repository = AndroidCalendarRepository(context.contentResolver, context.getString(org.monthlane.R.string.unnamed_calendar), context.getString(org.monthlane.R.string.untitled_event))
            val calendars = repository.getCalendars()
            if (calendars.isEmpty()) return CalendarSnapshot(CalendarStatus.NO_CALENDARS)
            val availableIds = calendars.map { it.id }.toSet()
            val selected = (CalendarSettings.selectedIds(context) ?: calendars.filter { it.visible }.map { it.id }.toSet()).intersect(availableIds)
            if (selected.isEmpty()) return CalendarSnapshot(CalendarStatus.NO_SELECTION, calendars)
            // All-day boundaries use UTC, so include a safety day around local month bounds.
            val from = month.atDay(1).atStartOfDay(zone).toInstant().minusSeconds(86400)
            val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().plusSeconds(86400)
            CalendarSnapshot(CalendarStatus.READY, calendars, repository.getEvents(from, to, selected))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            CalendarSnapshot(CalendarStatus.PERMISSION_REQUIRED)
        } catch (_: RuntimeException) {
            CalendarSnapshot(CalendarStatus.ERROR)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun observeMonth(context: Context, month: YearMonth) = callbackFlow {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { invalidate(); trySend(false) }
        }
        val prefs = context.getSharedPreferences(CalendarSettings.FILE, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> invalidate(); trySend(false) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        var registered = false
        if (CalendarSettings.canRead(context)) {
            try {
                resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
                registered = true
            } catch (_: SecurityException) { /* A concurrent permission revocation is handled by loadMonth. */ }
        }
        trySend(true)
        awaitClose {
            if (registered) resolver.unregisterContentObserver(observer)
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.conflate().debounce { initial -> if (initial) 0L else 150L }.mapLatest { month(context, month) }.flowOn(Dispatchers.IO)
}
