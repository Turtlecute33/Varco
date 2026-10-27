package app.varco.ssh

import com.yubico.yubikit.core.smartcard.SmartCardConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A question that a connection asks the user. The UI shows the oldest one and calls [answer]. */
sealed class Ask<T> {
    /** The server name, for the dialog title. */
    abstract val server: String
    internal val reply = CompletableDeferred<T>()

    fun answer(value: T) {
        reply.complete(value)
    }

    /** Host key not known (changed = false) or different from the trusted key (changed = true). */
    class HostKey(override val server: String, val host: String, val port: Int, val type: String,
                  val fingerprint: String, val changed: Boolean) : Ask<Boolean>()

    /**
     * Password; null = cancel. [save] in the answer is the "Save password" box. [saved] = the saved password
     * failed: the box starts on, so that the new password replaces it.
     */
    class Password(override val server: String, val user: String, val retry: Boolean, val saved: Boolean) :
        Ask<Password.Reply?>() {
        class Reply(val password: String, val save: Boolean)
    }

    /** Keyboard-interactive (for example a one-time code). One answer per prompt; null = cancel. */
    class Interactive(override val server: String, val name: String, val instruction: String,
                      val prompts: List<Field>) : Ask<List<String>?>() {
        class Field(val text: String, val echo: Boolean)
    }

    /** YubiKey PIN; null = cancel. [triesLeft] is set after a wrong PIN. */
    class Pin(override val server: String, val keyName: String, val triesLeft: Int?) : Ask<CharArray?>()

    /**
     * Waits for a YubiKey on NFC or USB and runs [use] with it, on a YubiKit thread, before the dialog
     * closes. The answer is the result (an error too), or null on cancel. A dropped NFC link waits for a new tap.
     */
    class Touch(
        override val server: String,
        val keyName: String,
        val use: (SmartCardConnection) -> ByteArray,
    ) : Ask<Result<ByteArray>?>()

    /** Passphrase of a key file being imported; null = skip this key. */
    class Passphrase(override val server: String, val keyName: String, val retry: Boolean) : Ask<CharArray?>()

    /**
     * Fingerprint or screen lock for a key with [app.varco.data.SshKey.authPerUse]. The UI authenticates
     * [signature] with BiometricPrompt and answers true; the connection then signs.
     */
    class Unlock(override val server: String, val keyName: String, val signature: java.security.Signature) :
        Ask<Boolean>()
}

/** Open questions of all connections. Connection threads block in [ask]; the UI answers in order. */
class Prompts {
    private val _open = MutableStateFlow<List<Ask<*>>>(emptyList())
    val open: StateFlow<List<Ask<*>>> = _open.asStateFlow()

    suspend fun <T> ask(question: Ask<T>): T {
        _open.update { it + question }
        try {
            return question.reply.await()
        } finally {
            _open.update { list -> list.filterNot { it === question } }
        }
    }
}
