package com.vscodroid.diligent

import com.vscodroid.diligent.WakePolicy.HOLD_MS
import com.vscodroid.diligent.WakePolicy.RENEW_MS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakePolicyTest {

    @Test
    fun `nothing running holds nothing`() {
        assertEquals(WakeAction.None, WakePolicy.decide(active = false, held = false, acquiredAt = null, now = 0))
        assertEquals(WakeAction.Release, WakePolicy.decide(active = false, held = true, acquiredAt = 0, now = 10))
    }

    @Test
    fun `a running task takes the lock`() {
        assertEquals(WakeAction.Acquire, WakePolicy.decide(active = true, held = false, acquiredAt = null, now = 5))
        // Lapsed by its own timeout: taken again.
        assertEquals(WakeAction.Acquire, WakePolicy.decide(active = true, held = false, acquiredAt = 0, now = HOLD_MS + 1))
        // Held but with no record of when: treated as new.
        assertEquals(WakeAction.Acquire, WakePolicy.decide(active = true, held = true, acquiredAt = null, now = 5))
    }

    @Test
    fun `the lock is renewed hourly and kept in between`() {
        val t0 = 1_000L
        assertEquals(WakeAction.Keep, WakePolicy.decide(true, true, t0, t0 + RENEW_MS - 1))
        assertEquals(WakeAction.Renew, WakePolicy.decide(true, true, t0, t0 + RENEW_MS))
        assertEquals(WakeAction.Renew, WakePolicy.decide(true, true, t0, t0 - 1), "a clock that went back renews")
    }

    @Test
    fun `every hold carries a timeout past the renewal`() {
        assertTrue(HOLD_MS > RENEW_MS)
        assertTrue(HOLD_MS <= RENEW_MS + 10 * 60 * 1000, "a lock nobody renews must lapse soon after the hour")
    }

    @Test
    fun `the next check falls due at the renewal`() {
        assertEquals(RENEW_MS, WakePolicy.nextCheckIn(100, 100))
        assertEquals(RENEW_MS - 60_000, WakePolicy.nextCheckIn(0, 60_000))
        assertEquals(0, WakePolicy.nextCheckIn(0, RENEW_MS * 2))
        assertEquals(RENEW_MS, WakePolicy.nextCheckIn(100, 0))
    }
}
