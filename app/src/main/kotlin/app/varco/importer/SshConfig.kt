package app.varco.importer

/**
 * Reads the server entries of an OpenSSH client config (`~/.ssh/config`).
 *
 * Only `Host` blocks with plain names become entries: patterns (`*`, `?`, `!`) and `Match` blocks are rules,
 * not servers. A block with more names ("Host vps 203.0.113.10") is one server, named by its first name.
 * Values from `Host *` blocks are not applied: Varco keeps each server complete. Of the options, only
 * HostName, User, Port and IdentityFile matter here; the first value wins, as in OpenSSH. ProxyJump and
 * ProxyCommand only mark the entry: Varco has no jump hosts (DESIGN.md, not in v1).
 */
object SshConfig {
    data class Entry(
        val alias: String,
        val host: String,
        val user: String?,
        val port: Int,
        /** File names of IdentityFile values (no folder), to match imported keys. */
        val identityFiles: List<String>,
        /** ProxyJump or ProxyCommand is set and is not "none": the server is not reachable without a jump host. */
        val needsJump: Boolean,
    )

    fun parse(text: String): List<Entry> {
        val entries = ArrayList<Entry>()
        var alias: String? = null
        var options = HashMap<String, MutableList<String>>()

        fun flush() {
            val name = alias ?: return
            entries += Entry(
                alias = name,
                host = options["hostname"]?.first()?.replace("%h", name) ?: name,
                user = options["user"]?.first(),
                port = options["port"]?.first()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                identityFiles = options["identityfile"].orEmpty().map { it.substringAfterLast('/') },
                needsJump = JUMP_OPTIONS.any { k -> options[k]?.first()?.equals("none", ignoreCase = true) == false },
            )
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val (key, value) = split(line) ?: continue
            when (key) {
                "host" -> {
                    flush()
                    alias = words(value).firstOrNull { name -> name.none { it in "*?!" } }
                    options = HashMap()
                }
                "match" -> {
                    flush()
                    alias = null
                    options = HashMap()
                }
                else -> options.getOrPut(key) { ArrayList() } += words(value).firstOrNull() ?: continue
            }
        }
        flush()
        // An alias that shows twice: the first block wins, as in OpenSSH.
        return entries.distinctBy { it.alias }
    }

    private val JUMP_OPTIONS = listOf("proxyjump", "proxycommand")

    /** "Key value", "Key=value" or "Key = value". The key is lower case. */
    private fun split(line: String): Pair<String, String>? {
        val end = line.indexOfFirst { it == ' ' || it == '\t' || it == '=' }
        if (end <= 0) return null
        val key = line.substring(0, end).lowercase()
        val value = line.substring(end).trimStart(' ', '\t').removePrefix("=").trim()
        return if (value.isEmpty()) null else key to value
    }

    /** Words split on blanks; double quotes keep blanks inside one word. */
    private fun words(value: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        var quoted = false
        for (c in value) {
            when {
                c == '"' -> quoted = !quoted
                (c == ' ' || c == '\t') && !quoted -> if (word.isNotEmpty()) {
                    out += word.toString()
                    word.clear()
                }
                else -> word.append(c)
            }
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }
}
