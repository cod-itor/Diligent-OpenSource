package com.vscodroid.diligent.projects

/**
 * One of git's progress lines, such as `Receiving objects:  45% (450/1000)`,
 * read into the step's name and how far it is. Git rewrites these lines in
 * place with a carriage return, and the shell hands each rewrite over as a
 * line, so the newest one is where the clone is.
 */
data class GitProgress(val step: String, val percent: Int) {
    companion object {
        private val LINE = Regex("""^(?:remote:\s*)?([A-Za-z][A-Za-z ]*?):\s+(\d{1,3})%""")

        /** The progress in [line], or null for any other line. */
        fun of(line: String): GitProgress? {
            val match = LINE.find(line.trim()) ?: return null
            val percent = match.groupValues[2].toInt().coerceIn(0, 100)
            return GitProgress(match.groupValues[1], percent)
        }
    }
}
