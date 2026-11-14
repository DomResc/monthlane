package org.monthlane.domain

import org.junit.Assert.*
import org.junit.Test
import org.monthlane.domain.model.Weather

class WeatherTest {
    @Test fun flagsMissingOldAndFutureTimestamps() {
        val now = 20_000_000L
        val weather = Weather("City", 12.0, null, null, "Rain", updatedAt = now)
        assertFalse(weather.stale(now))
        assertFalse(weather.copy(updatedAt = now - 3 * 60 * 60 * 1000L).stale(now))
        assertTrue(weather.copy(updatedAt = now - 3 * 60 * 60 * 1000L - 1).stale(now))
        assertTrue(weather.copy(updatedAt = null).stale(now))
        assertTrue(weather.copy(updatedAt = now + 6 * 60 * 1000L).stale(now))
    }
}
