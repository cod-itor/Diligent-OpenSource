package com.vscodroid.diligent.projects

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelativeTimeTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun ago(ms: Long) = RelativeTime.between(now - ms, now)

    @Test
    fun `under a minute is just now, and so is the future`() {
        assertEquals(RelativeTime.JustNow, ago(0))
        assertEquals(RelativeTime.JustNow, ago(59_999))
        assertEquals(RelativeTime.JustNow, ago(-day))
    }

    @Test
    fun `minutes and hours round down`() {
        assertEquals(RelativeTime.Minutes(1), ago(minute))
        assertEquals(RelativeTime.Minutes(59), ago(hour - 1))
        assertEquals(RelativeTime.Hours(1), ago(hour))
        assertEquals(RelativeTime.Hours(2), ago(2 * hour + 59 * minute))
        assertEquals(RelativeTime.Hours(23), ago(day - 1))
    }

    @Test
    fun `one day is yesterday, then days`() {
        assertEquals(RelativeTime.Yesterday, ago(day))
        assertEquals(RelativeTime.Yesterday, ago(2 * day - 1))
        assertEquals(RelativeTime.Days(2), ago(2 * day))
        assertEquals(RelativeTime.Days(6), ago(7 * day - 1))
    }

    @Test
    fun `then weeks, months and years`() {
        assertEquals(RelativeTime.Weeks(1), ago(7 * day))
        assertEquals(RelativeTime.Weeks(4), ago(29 * day))
        assertEquals(RelativeTime.Months(1), ago(30 * day))
        assertEquals(RelativeTime.Months(12), ago(364 * day))
        assertEquals(RelativeTime.Years(1), ago(365 * day))
        assertEquals(RelativeTime.Years(3), ago(3 * 365 * day + day))
    }
}
