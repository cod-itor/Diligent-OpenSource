package com.vscodroid.diligent.run

/** Where a profile runs, read from its `where`. */
sealed interface RunWhere {
    data object Phone : RunWhere

    /** `laptop:<name>`: the laptop of that name in `~/.diligent/machines.json`. */
    data class Laptop(val name: String) : RunWhere

    /** A value this version does not know (a later version's, or a typo): never run anywhere else instead. */
    data class Unknown(val raw: String) : RunWhere

    companion object {
        const val LAPTOP_PREFIX = "laptop:"

        fun parse(where: String): RunWhere {
            val w = where.trim()
            return when {
                w == RunProfile.WHERE_PHONE -> Phone
                w.startsWith(LAPTOP_PREFIX) && w.removePrefix(LAPTOP_PREFIX).isNotBlank() -> Laptop(w.removePrefix(LAPTOP_PREFIX).trim())
                else -> Unknown(w)
            }
        }

        fun laptop(name: String): String = LAPTOP_PREFIX + name
    }
}
