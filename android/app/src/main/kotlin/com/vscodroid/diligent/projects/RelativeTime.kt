package com.vscodroid.diligent.projects

/**
 * How long ago something happened, in the unit a person would say it in:
 * "just now", "5 minutes ago", "yesterday", "3 weeks ago". Worked out here
 * and worded by the screen, through plural resources, so the arithmetic is
 * tested without Android.
 */
sealed interface RelativeTime {
    data object JustNow : RelativeTime
    data class Minutes(val count: Int) : RelativeTime
    data class Hours(val count: Int) : RelativeTime
    data object Yesterday : RelativeTime
    data class Days(val count: Int) : RelativeTime
    data class Weeks(val count: Int) : RelativeTime
    data class Months(val count: Int) : RelativeTime
    data class Years(val count: Int) : RelativeTime

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
        private const val WEEK = 7 * DAY
        private const val MONTH = 30 * DAY
        private const val YEAR = 365 * DAY

        /**
         * [then] seen from [now]. A time in the future (a clock set back
         * since) reads as just now rather than as a negative count.
         */
        fun between(then: Long, now: Long): RelativeTime {
            val ago = now - then
            return when {
                ago < MINUTE -> JustNow
                ago < HOUR -> Minutes((ago / MINUTE).toInt())
                ago < DAY -> Hours((ago / HOUR).toInt())
                ago < 2 * DAY -> Yesterday
                ago < WEEK -> Days((ago / DAY).toInt())
                ago < MONTH -> Weeks((ago / WEEK).toInt())
                ago < YEAR -> Months((ago / MONTH).toInt())
                else -> Years((ago / YEAR).toInt())
            }
        }
    }
}
