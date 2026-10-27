package com.vscodroid.diligent

/** What to do with the partial wake lock. */
enum class WakeAction { Acquire, Renew, Release, Keep, None }

/**
 * When [TaskKeeper] holds its partial wake lock: only while a task runs or
 * restarts, never otherwise.
 *
 * The lock is always taken with a timeout of [HOLD_MS], a little over the
 * hour after which it is renewed, so a lock that nothing renews (the process
 * frozen, a missed timer) lapses by itself within the hour rather than
 * draining the battery for good.
 */
object WakePolicy {
    /** How long a held lock lives before it is renewed. */
    const val RENEW_MS = 60L * 60 * 1000

    /** The timeout each acquire carries: the renewal period and a margin for a late timer. */
    const val HOLD_MS = RENEW_MS + 5L * 60 * 1000

    /**
     * The action for a lock that is [held] (taken at [acquiredAt], in the
     * same clock as [now]) when [active] says whether any task runs.
     */
    fun decide(active: Boolean, held: Boolean, acquiredAt: Long?, now: Long): WakeAction = when {
        !active -> if (held) WakeAction.Release else WakeAction.None
        !held || acquiredAt == null -> WakeAction.Acquire
        now - acquiredAt >= RENEW_MS || now < acquiredAt -> WakeAction.Renew
        else -> WakeAction.Keep
    }

    /** How long until [decide] should be asked again, for a lock taken at [acquiredAt]. */
    fun nextCheckIn(acquiredAt: Long, now: Long): Long =
        (RENEW_MS - (now - acquiredAt)).coerceIn(0, RENEW_MS)
}
