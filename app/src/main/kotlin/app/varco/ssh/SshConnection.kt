package app.varco.ssh

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import app.varco.data.KnownHost
import app.varco.data.Server
import app.varco.data.SshKey
import app.varco.data.Store
import app.varco.keys.ChipKeys
import app.varco.keys.Piv
import app.varco.keys.SshWire
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.yubico.yubikit.core.application.InvalidPinException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Base64

/** The user stopped a prompt (password, PIN, host key, fingerprint). The connection ends without an error. */
class Cancelled : RuntimeException("Cancelled")

/** A key could not sign; [message] says why, in words for the user. */
class SignFailed(message: String, cause: Throwable) : RuntimeException(message, cause)

private fun signError(key: SshKey, e: Exception): String = when (e) {
    is KeyPermanentlyInvalidatedException ->
        "Android reset the key \"${key.name}\" (screen lock or fingerprints changed). Make a new key."
    is UserNotAuthenticatedException -> "Unlock the phone, then connect again."
    is Piv.WrongKeyException -> e.message.orEmpty()
    is IOException -> "The YubiKey connection ended. Hold the key still until the dialog closes."
    else -> "${key.name}: ${e.message ?: e.javaClass.simpleName}"
}

/** What connections need from the app. */
class SshEnv(val store: Store, val chip: ChipKeys, val prompts: Prompts)

/**
 * One authenticated SSH connection (jsch) to [server]. Strict host key checking: an unknown or changed host
 * key needs the user's yes. Only modern algorithms are offered, post-quantum key exchange first.
 * [connect] blocks (network and prompts); call it off the main thread.
 */
class SshConnection(private val env: SshEnv, val server: Server) {
    @Volatile
    private var session: Session? = null

    val jsch: Session get() = checkNotNull(session) { "Not connected" }

    val isConnected: Boolean get() = session?.isConnected == true

    /** Connects and signs in. [onBanner] gets the server's pre-login banner, if any. */
    fun connect(onBanner: (String) -> Unit) {
        val jsch = JSch()
        val key = server.keyId?.let { id -> env.store.state.value.keys.firstOrNull { it.id == id } }
        when (key?.kind) {
            SshKey.Kind.FILE -> jsch.addIdentity(key.name, requireNotNull(key.privateKey).toByteArray(), null, null)
            SshKey.Kind.KEYSTORE, SshKey.Kind.YUBIKEY_PIV -> jsch.addIdentity(ExternalIdentity(key), null)
            null -> Unit
        }
        val auth = Auth(onBanner)
        val s = jsch.getSession(server.user, server.host, server.port).apply {
            CONFIG.forEach { (k, v) -> setConfig(k, v) }
            setConfig(
                "PreferredAuthentications",
                if (key != null) "publickey,keyboard-interactive,password" else "keyboard-interactive,password",
            )
            hostKeyRepository = HostKeys()
            userInfo = auth
            setServerAliveInterval(KEEPALIVE_MS)
            serverAliveCountMax = KEEPALIVE_MAX
        }
        session = s
        s.connect(CONNECT_TIMEOUT_MS)
        auth.newPassword?.let { password ->
            env.store.update { st -> st.copy(servers = st.servers.map { if (it.id == server.id) it.copy(password = password) else it }) }
        }
    }

    /** Ends the connection, and with it any open prompt of this connection. */
    fun close() {
        askJob.cancel()
        session?.disconnect()
    }

    /** Parent of this connection's prompts: [close] cancels it, so that no dialog outlives its connection. */
    private val askJob = Job()

    private fun <T> ask(question: Ask<T>): T = try {
        runBlocking(askJob) { env.prompts.ask(question) }
    } catch (_: CancellationException) {
        throw Cancelled()
    }

    /** A key whose private part stays outside the app: Keystore (secure chip) or YubiKey PIV. */
    private inner class ExternalIdentity(private val key: SshKey) : Identity {
        override fun setPassphrase(passphrase: ByteArray?) = true
        override fun getPublicKeyBlob(): ByteArray = key.publicBlob
        override fun getSignature(data: ByteArray): ByteArray = getSignature(data, algName)
        override fun getSignature(data: ByteArray, alg: String): ByteArray = try {
            when (key.kind) {
                SshKey.Kind.KEYSTORE -> chipSign(data, alg)
                else -> pivSign(data, alg)
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            // jsch turns other exceptions into a bare "Auth fail"; this keeps the reason.
            throw SignFailed(signError(key, e), e)
        }

        // jsch picks rsa-sha2-512/256 for "ssh-rsa" keys from the server's server-sig-algs.
        override fun getAlgName(): String = key.type
        override fun getName(): String = key.name
        override fun isEncrypted() = false
        override fun clear() = Unit

        private fun chipSign(data: ByteArray, alg: String): ByteArray {
            val signature = env.chip.signer(key, alg)
            if (key.authPerUse && !ask(Ask.Unlock(server.name, key.name, signature))) throw Cancelled()
            signature.update(data)
            return SshWire.signature(alg, signature.sign())
        }

        private fun pivSign(data: ByteArray, alg: String): ByteArray {
            val slot = Piv.slot(requireNotNull(key.pivSlot))
            var triesLeft: Int? = null
            while (true) {
                val pin = ask(Ask.Pin(server.name, key.name, triesLeft)) ?: throw Cancelled()
                try {
                    // The dialog stays open until the YubiKey has signed, so the user holds the key long enough.
                    val result = ask(Ask.Touch(server.name, key.name) { connection ->
                        Piv.sign(connection, slot, key.publicBlob, pin, data, alg)
                    }) ?: throw Cancelled()
                    return result.getOrThrow()
                } catch (e: InvalidPinException) {
                    triesLeft = e.attemptsRemaining
                    if (triesLeft == 0) throw IllegalStateException("The YubiKey PIN is blocked. Unblock it with the PUK.")
                } finally {
                    pin.fill('\u0000')
                }
            }
        }
    }

    /** Password and keyboard-interactive. A saved password is tried once, then the user is asked. */
    private inner class Auth(private val onBanner: (String) -> Unit) : UserInfo, UIKeyboardInteractive {
        private var password: String? = null
        private var savedTried = false
        private var asked = 0

        /** A password the user asked to save; written after a successful sign-in. */
        var newPassword: String? = null
            private set

        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password
        override fun promptPassphrase(message: String?) = false

        // StrictHostKeyChecking=yes: jsch never asks; HostKeys asks the user itself.
        override fun promptYesNo(message: String?) = false

        override fun showMessage(message: String?) {
            if (!message.isNullOrBlank()) onBanner(message)
        }

        override fun promptPassword(message: String?): Boolean {
            if (!savedTried && server.password != null) {
                savedTried = true
                password = server.password
                return true
            }
            val reply = ask(Ask.Password(server.name, server.user, retry = asked++ > 0 || savedTried)) ?: return false
            password = reply.password
            newPassword = if (reply.save) reply.password else null
            return true
        }

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<String>,
            echo: BooleanArray,
        ): Array<String>? {
            if (prompt.isEmpty()) return emptyArray()
            if (prompt.size == 1 && !echo[0] && prompt[0].contains("password", ignoreCase = true) &&
                !savedTried && server.password != null
            ) {
                savedTried = true
                return arrayOf(server.password)
            }
            val fields = prompt.mapIndexed { i, text -> Ask.Interactive.Field(text, echo.getOrElse(i) { false }) }
            return ask(Ask.Interactive(server.name, name.orEmpty(), instruction.orEmpty(), fields))?.toTypedArray()
        }
    }

    /**
     * Host keys: Varco's own list first, then the known_hosts lines imported from Termux. A new key needs the
     * user's yes and is then saved. jsch calls this once per connection, on its connect thread.
     */
    private inner class HostKeys : HostKeyRepository {
        private val host = server.host.lowercase()

        override fun check(ignored: String?, key: ByteArray): Int {
            val type = SshWire.Reader(key).text()
            val encoded = Base64.getEncoder().encodeToString(key)
            val state = env.store.state.value
            val own = state.knownHosts.filter { it.host == host && it.port == server.port }
            val imported = KnownHosts.parse(state.importedKnownHosts).filter { KnownHosts.matches(it.hosts, host, server.port) }
            if (imported.any { it.revoked && it.key.contentEquals(key) }) {
                throw IllegalStateException("The host key of ${server.host} is marked as revoked in known_hosts")
            }
            val trusted = own.any { it.type == type && it.key == encoded } ||
                (own.none { it.type == type } && imported.any { !it.revoked && it.type == type && it.key.contentEquals(key) })
            // Any other known key counts: a server that suddenly offers only another key type is suspect too
            // (jsch asks for the known types first, see getHostKey).
            val changed = !trusted && (own.isNotEmpty() || imported.any { !it.revoked })
            if (!trusted) {
                val yes = ask(Ask.HostKey(server.name, server.host, server.port, type, SshWire.fingerprint(key), changed))
                if (!yes) return if (changed) HostKeyRepository.CHANGED else HostKeyRepository.NOT_INCLUDED
            }
            if (own.none { it.type == type && it.key == encoded }) {
                env.store.update { st ->
                    st.copy(
                        knownHosts = st.knownHosts.filterNot { it.host == host && it.port == server.port && it.type == type } +
                            KnownHost(host, server.port, type, encoded),
                    )
                }
            }
            return HostKeyRepository.OK
        }

        /** Known keys of this server (own and imported); jsch asks for their types first, so that a known key is used. */
        override fun getHostKey(ignored: String?, type: String?): Array<HostKey> {
            val state = env.store.state.value
            val own = state.knownHosts
                .filter { it.host == host && it.port == server.port }
                .map { Base64.getDecoder().decode(it.key) }
            val imported = KnownHosts.parse(state.importedKnownHosts)
                .filter { !it.revoked && KnownHosts.matches(it.hosts, host, server.port) }
                .map { it.key }
            return (own + imported)
                .filter { type == null || SshWire.Reader(it).text() == type }
                .mapNotNull { runCatching { HostKey(KnownHosts.name(host, server.port), it) }.getOrNull() }
                .toTypedArray()
        }

        override fun getHostKey(): Array<HostKey> = getHostKey(null, null)
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "Varco"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val KEEPALIVE_MS = 15_000
        const val KEEPALIVE_MAX = 3

        /** Algorithms in order of preference. No SHA-1, no CBC, no host certificates. */
        val CONFIG = mapOf(
            "kex" to "mlkem768x25519-sha256,sntrup761x25519-sha512,sntrup761x25519-sha512@openssh.com," +
                "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521," +
                "diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512," +
                "diffie-hellman-group14-sha256",
            "server_host_key" to "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256",
            "cipher.c2s" to CIPHERS,
            "cipher.s2c" to CIPHERS,
            "mac.c2s" to MACS,
            "mac.s2c" to MACS,
            "PubkeyAcceptedAlgorithms" to "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256",
            "StrictHostKeyChecking" to "yes",
            "HashKnownHosts" to "no",
        )
        const val CIPHERS = "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr"
        const val MACS = "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512"
    }
}
