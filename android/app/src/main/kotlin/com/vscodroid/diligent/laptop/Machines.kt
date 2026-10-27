package com.vscodroid.diligent.laptop

import com.vscodroid.diligent.run.RunConfig
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The laptop's system: what the Laptop screen's instructions are about. */
enum class MachineOs(val key: String) {
    MacOs("macos"),
    Linux("linux"),
    ;

    companion object {
        fun fromKey(key: String?): MachineOs? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A laptop the phone reaches over SSH: [user] at [host] (a Wi-Fi address or
 * a Tailscale name). [name] is what the user calls it, and what a run
 * profile's `"where": "laptop:<name>"` names.
 */
data class Machine(val name: String, val host: String, val user: String, val os: MachineOs) {
    /** `user@host`, as ssh and git take it. */
    val target: String get() = "$user@$host"
}

/** What is wrong with a machine typed into the form. */
enum class MachineProblem { NoName, BadName, BadHost, BadUser, Duplicate }

/**
 * The laptops in `~/.diligent/machines.json`:
 *
 * ```json
 * { "machines": [ { "name": "mac", "host": "192.168.1.20", "user": "me", "os": "macos" } ] }
 * ```
 *
 * The host and user go on ssh's command line, so they are held to the
 * characters a host name, an address and a login name use, and neither may
 * start with `-`, which ssh would read as an option. The name is free text
 * for people (quoted wherever a script uses it), without line breaks.
 * A malformed entry is skipped when reading rather than failing the file.
 */
object Machines {
    const val FILE = "machines.json"
    private const val MAX_NAME = 40

    private val HOST = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,252}")
    private val USER = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,63}")

    fun fileIn(homeDir: File): File = File(File(homeDir, ".diligent"), FILE)

    fun isHost(host: String): Boolean = HOST.matches(host)
    fun isUser(user: String): Boolean = USER.matches(user)

    fun isName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_NAME && name == name.trim() && name.none { it.isISOControl() }

    /** The first problem with [machine] next to [others] (the machine being edited left out), or null. */
    fun problem(machine: Machine, others: List<Machine>): MachineProblem? = when {
        machine.name.isBlank() -> MachineProblem.NoName
        !isName(machine.name) -> MachineProblem.BadName
        !isHost(machine.host) -> MachineProblem.BadHost
        !isUser(machine.user) -> MachineProblem.BadUser
        others.any { it.name == machine.name } -> MachineProblem.Duplicate
        else -> null
    }

    fun parse(text: String): List<Machine> {
        val array = try {
            JSONObject(text).optJSONArray("machines")
        } catch (_: JSONException) {
            null
        } ?: return emptyList()
        val out = ArrayList<Machine>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val m = Machine(
                name = (o.opt("name") as? String)?.trim() ?: continue,
                host = (o.opt("host") as? String)?.trim() ?: continue,
                user = (o.opt("user") as? String)?.trim() ?: continue,
                os = MachineOs.fromKey(o.opt("os") as? String) ?: MachineOs.MacOs,
            )
            if (problem(m, out) == null) out += m
        }
        return out
    }

    fun toJson(machines: List<Machine>): String = buildString {
        append("{\n  \"machines\": [")
        machines.forEachIndexed { i, m ->
            append(if (i == 0) "\n" else ",\n")
            append("    { \"name\": ").append(RunConfig.jsonString(m.name))
            append(", \"host\": ").append(RunConfig.jsonString(m.host))
            append(", \"user\": ").append(RunConfig.jsonString(m.user))
            append(", \"os\": ").append(RunConfig.jsonString(m.os.key)).append(" }")
        }
        append(if (machines.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
    }

    /** Blocking. An absent or unreadable file is no machines. */
    fun load(homeDir: File): List<Machine> = try {
        val f = fileIn(homeDir)
        if (f.isFile && f.length() < 256 * 1024) parse(f.readText()) else emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    /** Blocking. Written through a temporary file and a rename. */
    @Throws(IOException::class)
    fun save(homeDir: File, machines: List<Machine>) {
        val f = fileIn(homeDir)
        val dir = f.parentFile ?: throw IOException("no parent for $f")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(toJson(machines))
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("cannot replace $f")
        }
    }
}
