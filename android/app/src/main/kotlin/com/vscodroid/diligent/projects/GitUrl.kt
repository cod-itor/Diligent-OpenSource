package com.vscodroid.diligent.projects

/**
 * What Clone from Git accepts, and the folder name it suggests.
 *
 * The check is a shape check, not a promise: it catches a pasted sentence,
 * a URL with a space in it, or a web page address with no repository path,
 * before git spends a network round trip saying so less kindly. Whether the
 * repository exists is git's answer to give.
 */
object GitUrl {

    private val SCHEME = Regex("""^(https?|ssh|git|git\+ssh)://[^/\s]+/\S+$""", RegexOption.IGNORE_CASE)

    /** `git@github.com:user/repo`, the scp-like form ssh remotes are usually written in. */
    private val SCP_LIKE = Regex("""^[\w.~-]+@[\w.-]+:[^/\s]\S*$""")

    /** Whether [input] looks like something `git clone` can take: https, ssh, git@host:path, or ending in .git. */
    fun isValid(input: String): Boolean {
        val url = input.trim()
        if (url.isEmpty() || url.startsWith('-') || url.any { it.isWhitespace() || it.isISOControl() }) return false
        return SCHEME.matches(url) || SCP_LIKE.matches(url) || url.removeSuffix("/").endsWith(".git", ignoreCase = true)
    }

    /**
     * The folder `git clone` itself would make for [input]: the last part of
     * the path, without `.git`. `https://github.com/user/shop.git` gives
     * `shop`. Null when there is no usable name, such as a bare host.
     */
    fun folderName(input: String): String? {
        var path = input.trim()
        if (SCHEME.matches(path) || path.contains("://")) {
            path = path.substringAfter("://").substringBefore('?').substringBefore('#')
            if ('/' !in path.trimEnd('/')) return null
        }
        path = path.trimEnd('/').removeSuffix("/.git").trimEnd('/')
        val last = path.substringAfterLast('/').substringAfterLast(':')
        val name = last.removeSuffix(".git").removeSuffix(".GIT")
        return name.takeIf { ProjectNames.problem(it) { false } == null && !it.startsWith('-') }
    }
}
