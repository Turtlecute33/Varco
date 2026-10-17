package app.varco.data

import app.varco.term.Scheme
import java.util.Base64

/** All saved data. One immutable value; [Store] writes it encrypted as a whole. */
data class AppState(
    val servers: List<Server> = emptyList(),
    val keys: List<SshKey> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
    /** known_hosts lines imported from Termux, as they were (names may be hashed); used when [knownHosts] has no entry. */
    val importedKnownHosts: String = "",
    val schemes: List<Scheme> = emptyList(),
    val settings: Settings = Settings(),
)

data class Server(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    /** The key to try first; null = password or keyboard-interactive only. */
    val keyId: String? = null,
    /** Saved password, or null to ask on each connection. Stored only inside the encrypted store. */
    val password: String? = null,
    val lastUsed: Long = 0,
)

/**
 * A key that Varco can sign with. The private part is never in this object, except for [Kind.FILE] keys
 * that the secure chip cannot hold.
 */
data class SshKey(
    val id: String,
    val name: String,
    val kind: Kind,
    /** SSH key type, for example "ssh-ed25519". */
    val type: String,
    /** Public key blob (SSH wire form), base64. */
    val publicKey: String,
    /** [Kind.KEYSTORE]: the AndroidKeyStore alias. */
    val alias: String? = null,
    /** [Kind.KEYSTORE]: each use asks for fingerprint or screen lock. */
    val authPerUse: Boolean = false,
    /** [Kind.KEYSTORE]: StrongBox (true) or TEE (false). */
    val strongBox: Boolean = false,
    /** [Kind.YUBIKEY_PIV]: the slot, for example 0x9a. */
    val pivSlot: Int? = null,
    /** [Kind.FILE]: the private key in OpenSSH form, without passphrase. Stored only inside the encrypted store. */
    val privateKey: String? = null,
) {
    enum class Kind { KEYSTORE, YUBIKEY_PIV, FILE }

    val publicBlob: ByteArray get() = Base64.getDecoder().decode(publicKey)
}

data class Snippet(
    val id: String,
    val name: String,
    val text: String,
    /** Sends Enter after the text. */
    val run: Boolean = true,
)

/** A trusted host key. */
data class KnownHost(
    val host: String,
    val port: Int,
    val type: String,
    /** Public key blob, base64. */
    val key: String,
)

data class Settings(
    val schemeId: String? = null,
    val fontSize: Int = 13,
    /** "jetbrains-mono" (built in) or "custom" (imported TTF in the app files). */
    val font: String = "jetbrains-mono",
    val appLock: Boolean = false,
    /** Seconds in background before the lock comes back. 0 = on every open. */
    val lockAfter: Int = 60,
    /** Blocks screenshots, screen recording and the Recents preview. */
    val secureWindow: Boolean = true,
    val keepScreenOn: Boolean = false,
    val vibrateOnBell: Boolean = true,
    /** Lets the server set the phone clipboard (OSC 52). Off: a server could put text there silently. */
    val serverClipboard: Boolean = false,
    /** Lines kept in scrollback. */
    val scrollback: Int = 5000,
)
