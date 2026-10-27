package com.vscodroid.diligent

import org.json.JSONException
import org.json.JSONObject

/**
 * The file in front in the editor, as the diligent-bridge extension reports it
 * (`diligentActiveFile` in docs/05-API_SPEC.md): what Mobile view's file name
 * chip shows.
 */
data class ActiveFile(
    /** Absolute path, or null for an untitled file. */
    val path: String?,
    /** The name to show. */
    val name: String,
    /** Has unsaved changes. */
    val dirty: Boolean,
) {
    companion object {
        /**
         * Reads the bridge's JSON. Answers [Result.None] when no text editor is
         * active (no name), and null for text that is not a JSON object, which
         * the caller ignores.
         */
        fun parse(json: String): Result? {
            val obj = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
            val name = obj.optStringOrNull("name") ?: return Result.None
            return Result.File(ActiveFile(obj.optStringOrNull("path"), name, obj.optBoolean("dirty", false)))
        }

        /** A non-blank string field, or null for a missing, null or blank one. */
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else (opt(key) as? String)?.takeIf { it.isNotBlank() }
    }

    /** What a message says: a file is in front, or none is. */
    sealed interface Result {
        data class File(val file: ActiveFile) : Result
        data object None : Result
    }
}
