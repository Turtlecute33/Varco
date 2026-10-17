package app.varco.ssh

import app.varco.keys.SshWire
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OpenSSH `known_hosts` lines: plain names, hashed names (`|1|salt|hash`), `[host]:port`, wildcards and
 * negation. `@revoked` lines are kept (they block a key); `@cert-authority` lines are dropped, because Varco
 * does not use host certificates.
 */
object KnownHosts {
    class Line(val hosts: String, val type: String, val key: ByteArray, val revoked: Boolean)

    fun parse(text: String): List<Line> = text.lineSequence().mapNotNull(::parseLine).toList()

    private fun parseLine(raw: String): Line? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith('#')) return null
        var fields = line.split(' ', '\t').filter { it.isNotEmpty() }
        var revoked = false
        if (fields.first().startsWith('@')) {
            when (fields.first()) {
                "@revoked" -> revoked = true
                else -> return null
            }
            fields = fields.drop(1)
        }
        if (fields.size < 3) return null
        // A real key line: the blob decodes and starts with its own type name.
        val key = runCatching { Base64.getDecoder().decode(fields[2]) }.getOrNull() ?: return null
        val type = runCatching { SshWire.Reader(key).text() }.getOrNull()
        if (type != fields[1]) return null
        return Line(fields[0], fields[1], key, revoked)
    }

    /** The name form that OpenSSH writes: "host" for port 22, else "[host]:port". */
    fun name(host: String, port: Int): String = if (port == 22) host else "[$host]:$port"

    /** True when the host field [hosts] of a line covers [host] on [port]. */
    fun matches(hosts: String, host: String, port: Int): Boolean {
        val name = name(host.lowercase(), port)
        if (hosts.startsWith("|1|")) return hashedMatches(hosts, name)
        var hit = false
        for (pattern in hosts.lowercase().split(',')) {
            val negated = pattern.startsWith('!')
            val p = pattern.removePrefix("!")
            if (wildcard(p, name)) {
                if (negated) return false
                hit = true
            }
        }
        return hit
    }

    private fun hashedMatches(field: String, name: String): Boolean {
        val parts = field.split('|') // "", "1", salt, hash
        if (parts.size != 4) return false
        return try {
            val salt = Base64.getDecoder().decode(parts[2])
            val hash = Base64.getDecoder().decode(parts[3])
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(salt, "HmacSHA1")) }
            java.security.MessageDigest.isEqual(mac.doFinal(name.toByteArray()), hash)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** `*` = any run of characters, `?` = one character. */
    internal fun wildcard(pattern: String, text: String): Boolean {
        var p = 0
        var t = 0
        var star = -1
        var mark = 0
        while (t < text.length) {
            when {
                p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t]) -> { p++; t++ }
                p < pattern.length && pattern[p] == '*' -> { star = p++; mark = t }
                star >= 0 -> { p = star + 1; t = ++mark }
                else -> return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }
}
