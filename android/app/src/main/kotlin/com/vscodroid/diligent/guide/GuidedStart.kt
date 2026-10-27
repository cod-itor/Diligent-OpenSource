package com.vscodroid.diligent.guide

import android.content.SharedPreferences
import androidx.core.content.edit
import com.vscodroid.diligent.projects.ProjectNames

/**
 * What a beginner picks first in the guided start, and the kit recipe that
 * makes it. "I'm new to coding" makes the same website as [Website] and also
 * turns beginner mode on.
 */
enum class GuideGoal(
    /** The kit recipe's id (`assets/extensions/diligent.diligent-kit-<version>/recipes/<id>.json`). */
    val recipe: String,
    /** The name offered in the name step, before a suffix if it is taken. */
    val defaultName: String,
    /** Whether picking it turns beginner mode on. */
    val beginner: Boolean,
    /** The card shown once the project is open. */
    val nextStep: NextStep,
) {
    Website("web-basic", "my-site", beginner = false, NextStep.RunPage),
    React("react-vite", "my-react-app", beginner = false, NextStep.RunApp),
    Python("python-script", "my-python", beginner = false, NextStep.RunScript),
    NewToCoding("web-basic", "my-first-site", beginner = true, NextStep.RunPage),
}

/** The "Next step" card after a guided start: what to tap to see the project work. */
enum class NextStep { RunPage, RunApp, RunScript }

/** The guided start's screens, in order: what to make, its name, then a last look before it starts. */
enum class GuideStep {
    Goal, Name, Confirm;

    /** The step Back returns to, or null on the first, where Back closes the guide. */
    val previous: GuideStep? get() = entries.getOrNull(ordinal - 1)
}

/**
 * Whether the guided start was already offered after the first view choice,
 * so it shows once on its own; New project offers it any time after that.
 * Kept in Diligent's preferences file next to the view.
 */
class GuideSettings(private val prefs: SharedPreferences) {
    var offered: Boolean
        get() = prefs.getBoolean(KEY_OFFERED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_OFFERED, value) }
        }

    companion object {
        internal const val KEY_OFFERED = "guided_start_offered"
    }
}

/** The name step's first suggestion: [goal]'s default, or the first free one after it. */
fun suggestedName(goal: GuideGoal, taken: (String) -> Boolean): String = ProjectNames.free(goal.defaultName, taken)
