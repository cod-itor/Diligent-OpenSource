package com.vscodroid.diligent.devices

import com.ditarector.diligent.sync.device.DeviceRegistry
import com.ditarector.diligent.sync.manifest.Manifest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Automatic sync is on for this project folder with this device (Part D 3). */
data class AutoSyncKey(val deviceId: String, val projectPath: String)

/** diligent-link's settings on this device. */
data class LinkSettings(
    val visibility: Visibility = Visibility.WhileScreenOpen,
    val stayReachable: Boolean = false,
    val connectionMode: ConnectionMode = ConnectionMode.Automatic,
    val autoSync: Set<AutoSyncKey> = emptySet(),
)

/**
 * diligent-link's files in app data, never inside a project (SPEC A.7):
 * the paired devices, the settings, which project folder holds which
 * project ID, and the baseline manifest agreed with each device for each
 * project. Every write goes to a temporary file first and is renamed into
 * place, so a crash leaves the old file, not half a new one.
 */
class LinkStorage(private val dir: File) {
    private val registryFile get() = File(dir, "devices.json")
    private val settingsFile get() = File(dir, "settings.json")
    private val projectsFile get() = File(dir, "projects.json")

    /** Where received files wait before they are checked and renamed into place. */
    fun tempDir(projectId: String): File = File(dir, "incoming/" + JvmHashing.sha256Hex(projectId.toByteArray()).take(16))

    /** Where the device identity lives. */
    val identityDir: File get() = File(dir, "identity")

    fun loadRegistry(): DeviceRegistry = read(registryFile)?.let {
        try {
            DeviceRegistry.fromJson(it)
        } catch (e: IllegalArgumentException) {
            null
        }
    } ?: DeviceRegistry()

    fun saveRegistry(registry: DeviceRegistry) = write(registryFile, registry.toJson())

    fun loadSettings(): LinkSettings {
        val json = read(settingsFile)?.let { text -> parse(text) } ?: return LinkSettings()
        val auto = json.optJSONArray("autoSync") ?: JSONArray()
        return LinkSettings(
            visibility = Visibility.entries.firstOrNull { it.name == json.optString("visibility") } ?: Visibility.WhileScreenOpen,
            stayReachable = json.optBoolean("stayReachable", false),
            connectionMode = ConnectionMode.entries.firstOrNull { it.name == json.optString("connectionMode") } ?: ConnectionMode.Automatic,
            autoSync = (0 until auto.length()).mapNotNull { i ->
                auto.optJSONObject(i)?.let { AutoSyncKey(it.optString("deviceId"), it.optString("projectPath")) }
            }.filter { it.deviceId.isNotEmpty() && it.projectPath.isNotEmpty() }.toSet(),
        )
    }

    fun saveSettings(settings: LinkSettings) {
        val auto = JSONArray()
        settings.autoSync.forEach { auto.put(JSONObject().put("deviceId", it.deviceId).put("projectPath", it.projectPath)) }
        write(
            settingsFile,
            JSONObject()
                .put("visibility", settings.visibility.name)
                .put("stayReachable", settings.stayReachable)
                .put("connectionMode", settings.connectionMode.name)
                .put("autoSync", auto)
                .toString(),
        )
    }

    /** Project ID to folder, for the projects this device synced. */
    fun loadProjects(): MutableMap<String, String> {
        val json = read(projectsFile)?.let(::parse) ?: return LinkedHashMap()
        return json.keys().asSequence().associateWithTo(LinkedHashMap()) { json.optString(it) }
    }

    fun saveProjects(projects: Map<String, String>) {
        val json = JSONObject()
        projects.forEach { (id, path) -> json.put(id, path) }
        write(projectsFile, json.toString())
    }

    private fun baselineFile(deviceId: String, projectId: String) =
        File(dir, "baselines/" + deviceId + "/" + JvmHashing.sha256Hex(projectId.toByteArray()).take(32) + ".json")

    fun loadBaseline(deviceId: String, projectId: String): Manifest? = read(baselineFile(deviceId, projectId))?.let {
        try {
            Manifest.fromJson(it)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Stores the agreed manifest, or deletes it when [manifest] is null (next sync uses first-sync rules). */
    fun saveBaseline(deviceId: String, projectId: String, manifest: Manifest?) {
        val file = baselineFile(deviceId, projectId)
        if (manifest == null) file.delete() else write(file, manifest.toJson())
    }

    /** Forgets every baseline with [deviceId]. */
    fun forgetBaselines(deviceId: String) {
        File(dir, "baselines/$deviceId").deleteRecursively()
    }

    private fun parse(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (e: JSONException) {
        null
    }

    private fun read(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (e: IOException) {
        null
    }

    private fun write(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: IOException) {
            // Settings are rewritten on the next change; a failed write loses only this one.
        }
    }
}
