package com.vscodroid.diligent.run

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Remembers which projects have been told why their Next.js `dev` runs with
 * `--webpack`, so the note shows once per project and not on every visit.
 *
 * In Diligent's own preferences file, keyed by the project's folder: the note
 * is about this device (Turbopack's missing Android build), so it does not
 * belong in the project's `run.json`.
 */
class RunNotes(private val prefs: SharedPreferences) {

    /** Whether the Run screen should explain the `--webpack` in [profile]'s command for [projectDir]. */
    fun showWebpackNote(projectDir: String, profile: RunProfile?): Boolean =
        profile != null && isWebpackRewrite(profile.command) && projectDir !in seen()

    fun dismissWebpackNote(projectDir: String) {
        prefs.edit { putStringSet(KEY_WEBPACK_NOTE_SEEN, seen() + projectDir) }
    }

    private fun seen(): Set<String> = prefs.getStringSet(KEY_WEBPACK_NOTE_SEEN, null).orEmpty()

    companion object {
        internal const val KEY_WEBPACK_NOTE_SEEN = "run_webpack_note_seen"

        /** A command that passes `--webpack` on to its script, as [RunDetector] writes for Next.js. */
        fun isWebpackRewrite(command: String): Boolean {
            val words = command.trim().split(Regex("\\s+"))
            val dashes = words.indexOf("--")
            return dashes >= 0 && RunDetector.WEBPACK_FLAG in words.drop(dashes + 1)
        }
    }
}
