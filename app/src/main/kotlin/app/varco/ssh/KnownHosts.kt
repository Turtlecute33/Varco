package app.varco.ssh

import app.varco.data.KnownHost
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

    /** The trust decision for a host key (DESIGN, Security rules). */
    enum class Verdict { TRUSTED, NEW, CHANGED, REVOKED }

    /**
     * The trust rule for the host key [key] of type [type]. [own] are the keys that the user trusted for this
     * host and port, [imported] are the known_hosts lines that match them. An own key of the same type wins over
     * imported lines. A key that is not trusted is CHANGED when any other key is known, also of another type:
     * a server that suddenly offers only another key type is suspect too (jsch asks for the known types first).
     */
    fun verdict(own: List<KnownHost>, imported: List<Line>, type: String, key: ByteArray): Verdict {
        if (imported.any { it.revoked && it.key.contentEquals(key) }) return Verdict.REVOKED
        val encoded = Base64.getEncoder().encodeToString(key)
        val trusted = own.any { it.type == type && it.key == encoded } ||
            (own.none { it.type == type } && imported.any { !it.revoked && it.type == type && it.key.contentEquals(key) })
        return when {
            trusted -> Verdict.TRUSTED
            own.isNotEmpty() || imported.any { !it.revoked } -> Verdict.CHANGED
            else -> Verdict.NEW
        }
    }

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
