package app.varco.importer

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import app.varco.data.Server
import app.varco.data.SshKey
import app.varco.data.Store
import app.varco.keys.ChipKeys
import app.varco.keys.OpenSshKey
import app.varco.keys.SshWire
import app.varco.ssh.Ask
import app.varco.ssh.KnownHosts
import app.varco.ssh.Prompts
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.CharBuffer
import java.util.Base64
import java.util.UUID

/**
 * Imports files that the user picked (for example from Termux's home folder): private keys, ~/.ssh/config,
 * known_hosts, ~/.termux/colors.properties and ~/.termux/font.ttf. The type comes from the content, not
 * the file name. Keys move into the secure chip when the phone can hold their type.
 */
class Importer(
    private val context: Context,
    private val store: Store,
    private val chip: ChipKeys,
    private val prompts: Prompts,
) {
    data class Result(
        val keys: Int = 0,
        val keysInChip: Int = 0,
        val servers: Int = 0,
        val knownHosts: Int = 0,
        val scheme: Boolean = false,
        val font: Boolean = false,
        /** Names of files that were not imported, with the reason. */
        val skipped: List<String> = emptyList(),
    )

    private enum class Kind { PRIVATE_KEY, PUBLIC_KEY, SSH_CONFIG, KNOWN_HOSTS, COLORS, FONT, UNKNOWN }

    private class Picked(val name: String, val bytes: ByteArray) {
        val text: String by lazy { bytes.toString(Charsets.UTF_8) }
    }

    suspend fun import(uris: List<Uri>): Result = withContext(Dispatchers.IO) {
        val skipped = ArrayList<String>()
        val files = uris.mapNotNull { uri ->
            runCatching { read(uri) }.onFailure { skipped += "${uri.lastPathSegment}: cannot read" }.getOrNull()
        }
        val byKind = files.groupBy(::classify)
        byKind[Kind.UNKNOWN].orEmpty().forEach { skipped += "${it.name}: not a key, config or theme file" }

        // Keys first: config entries refer to them by file name.
        var keys = 0
        var inChip = 0
        val keyIdsByFile = HashMap<String, String>()
        for (file in byKind[Kind.PRIVATE_KEY].orEmpty()) {
            val key = try {
                importKey(file)
            } catch (e: Exception) {
                skipped += "${file.name}: ${e.message ?: "cannot read the key"}"
                continue
            }
            when (key) {
                null -> skipped += "${file.name}: skipped (no passphrase)"
                else -> {
                    keyIdsByFile[file.name] = key.id
                    keys++
                    if (key.kind == SshKey.Kind.KEYSTORE) inChip++
                }
            }
        }

        var servers = 0
        for (file in byKind[Kind.SSH_CONFIG].orEmpty()) servers += importConfig(file, keyIdsByFile)

        var hosts = 0
        val knownHostsText = byKind[Kind.KNOWN_HOSTS].orEmpty().joinToString("\n") { it.text }
        if (knownHostsText.isNotBlank()) {
            val lines = knownHostsText.lines().filter { line -> KnownHosts.parse(line).isNotEmpty() }
            store.update { st ->
                val existing = st.importedKnownHosts.lines().toSet()
                val new = lines.filterNot { it in existing }
                hosts = new.size
                st.copy(importedKnownHosts = (st.importedKnownHosts.lines() + new).filter { it.isNotBlank() }.joinToString("\n"))
            }
        }

        var scheme = false
        byKind[Kind.COLORS].orEmpty().lastOrNull()?.let { file ->
            val parsed = TermuxColors.parse(file.text, "custom:" + UUID.randomUUID(), "Termux import")
            if (parsed != null) {
                store.update { st -> st.copy(schemes = st.schemes + parsed, settings = st.settings.copy(schemeId = parsed.id)) }
                scheme = true
            }
        }

        var font = false
        byKind[Kind.FONT].orEmpty().lastOrNull()?.let { file ->
            if (saveFont(file.bytes)) {
                store.update { st -> st.copy(settings = st.settings.copy(font = FONT_CUSTOM)) }
                font = true
            } else {
                skipped += "${file.name}: the font cannot be used"
            }
        }

        Result(keys, inChip, servers, hosts, scheme, font, skipped)
    }

    /** Asks for the passphrase when needed. Returns null when the user skips the key. */
    private suspend fun importKey(file: Picked): SshKey? {
        val pair = KeyPair.load(JSch(), file.bytes, null)
        try {
            var retry = false
            while (pair.isEncrypted) {
                val pass = prompts.ask(Ask.Passphrase("Import", file.name, retry)) ?: return null
                // Encodes without a String, so that the passphrase can be wiped from memory.
                val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(pass))
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                pass.fill('\u0000')
                buffer.array().fill(0)
                pair.decrypt(bytes)
                bytes.fill(0)
                retry = true
            }
            val pem = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, null) }.toString(Charsets.US_ASCII.name())
            val parsed = OpenSshKey.parse(pem)
            val type = SshWire.Reader(parsed.publicBlob).text()
            require(type in USABLE_TYPES) { "$type keys are not supported" }
            val blob = Base64.getEncoder().encodeToString(parsed.publicBlob)
            store.state.value.keys.firstOrNull { it.publicKey == blob }?.let { return it } // already there
            val name = file.name.ifBlank { parsed.comment.ifBlank { "Imported key" } }
            val key = chip.import(parsed, name, authPerUse = false) ?: SshKey(
                id = UUID.randomUUID().toString(),
                name = name,
                kind = SshKey.Kind.FILE,
                type = type,
                publicKey = blob,
                privateKey = pem,
            )
            store.update { st -> st.copy(keys = st.keys + key) }
            return key
        } finally {
            pair.dispose()
        }
    }

    private fun importConfig(file: Picked, keyIdsByFile: Map<String, String>): Int {
        val entries = SshConfig.parse(file.text)
        var added = 0
        store.update { st ->
            val keysByName = st.keys.associate { it.name to it.id } + keyIdsByFile
            // Without IdentityFile, OpenSSH tries its default files in this order.
            val defaultKey = DEFAULT_KEYS.firstNotNullOfOrNull { keysByName[it] }
            val new = entries
                .filter { e -> st.servers.none { it.host.equals(e.host, true) && it.port == e.port && it.user == e.user.orEmpty() } }
                .map { e ->
                    Server(
                        id = UUID.randomUUID().toString(),
                        name = e.alias,
                        host = e.host,
                        port = e.port,
                        user = e.user.orEmpty(),
                        keyId = e.identityFiles.firstNotNullOfOrNull { keysByName[it] } ?: defaultKey,
                    )
                }
            added = new.size
            st.copy(servers = st.servers + new)
        }
        return added
    }

    private fun saveFont(bytes: ByteArray): Boolean {
        val temp = File(context.cacheDir, "font-import.ttf")
        return try {
            temp.writeBytes(bytes)
            Typeface.Builder(temp).build() ?: return false
            temp.copyTo(fontFile(context), overwrite = true)
            true
        } catch (_: Exception) {
            false
        } finally {
            temp.delete()
        }
    }

    private fun read(uri: Uri): Picked {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                require(out.size() <= MAX_BYTES) { "File too large" }
            }
            out.toByteArray()
        } ?: throw IllegalStateException("Cannot open $uri")
        return Picked(name, bytes)
    }

    private fun classify(file: Picked): Kind {
        val b = file.bytes
        if (b.size >= 4) {
            val magic = String(b, 0, 4, Charsets.ISO_8859_1)
            if (magic == "\u0000\u0001\u0000\u0000" || magic == "OTTO" || magic == "true" || magic == "ttcf") return Kind.FONT
        }
        val text = file.text
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
        return when {
            "PRIVATE KEY-----" in text || text.startsWith("PuTTY-User-Key-File") -> Kind.PRIVATE_KEY
            lines.isEmpty() -> Kind.UNKNOWN
            lines.all { KEY_LINE.matches(it) } -> Kind.PUBLIC_KEY
            lines.any { HOST_LINE.matches(it) } -> Kind.SSH_CONFIG
            lines.all { KnownHosts.parse(it).isNotEmpty() || it.startsWith("@") } -> Kind.KNOWN_HOSTS
            lines.any { COLOR_LINE.matches(it) } -> Kind.COLORS
            else -> Kind.UNKNOWN
        }
    }

    companion object {
        const val FONT_CUSTOM = "custom"
        private const val MAX_BYTES = 20 * 1024 * 1024
        private val DEFAULT_KEYS = listOf("id_ed25519", "id_ecdsa", "id_rsa")

        /** Key types that Varco offers to servers (DSA and FIDO keys are not). */
        private val USABLE_TYPES = setOf(
            "ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
        )
        private val KEY_LINE = Regex("""^(ssh-|ecdsa-|sk-)\S+\s+[A-Za-z0-9+/=]+(\s.*)?$""")
        private val HOST_LINE = Regex("""^host\s+\S.*$""", RegexOption.IGNORE_CASE)
        private val COLOR_LINE = Regex("""^(foreground|background|cursor|color\d{1,3})\s*[=:].+$""", RegexOption.IGNORE_CASE)

        fun fontFile(context: Context) = File(context.filesDir, "font.ttf")
    }
}
