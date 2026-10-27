package app.varco.ssh

import app.varco.data.Server
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.Executors

/** Server text that Varco writes itself (banner, end reason): no control sequences, because a banner comes before sign-in. */
internal fun printable(s: String): String = s.replace(Regex("\u001b\\[[0-?]*[ -/]*[@-~]"), "")
    .filter { it == '\n' || it == '\t' || (it >= ' ' && it != '\u007f' && it !in '\u0080'..'\u009f') }

/**
 * One terminal tab: a [TerminalSession] (screen and scrollback) and the SSH shell that feeds it. After the
 * connection ends, the tab stays with its scrollback; [reconnect] opens a new connection in the same tab.
 */
class Shell(
    val server: Server,
    val term: TerminalSession,
    private val env: SshEnv,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Connecting : State
        data object Connected : State
        /** [message] = why it ended; null when the remote shell ended normally (exit). */
        data class Closed(val message: String?) : State
    }

    val id: String = UUID.randomUUID().toString()

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Title set by the remote side (OSC 0/2), or null. */
    val title = MutableStateFlow<String?>(null)

    private val _target = MutableStateFlow(server)

    /** The server copy of the last connection (with saved edits): the session list shows its user@host. */
    val target: StateFlow<Server> = _target.asStateFlow()

    /** Network work that the main thread must not do: window-change requests and disconnects. */
    private val io = Executors.newSingleThreadExecutor { Thread(it, "VarcoShellIo") }

    @Volatile
    private var connection: SshConnection? = null

    @Volatile
    private var channel: ChannelShell? = null
    private var job: Job? = null

    /** The open connection, for SFTP on the same login; null when not connected. */
    val openConnection: SshConnection? get() = connection?.takeIf { _state.value == State.Connected }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun reconnect() {
        if (_state.value is State.Closed) start()
    }

    /** Ends the connection; the tab stays (see [Sessions.close] to remove it). */
    fun disconnect() {
        io.execute {
            channel?.disconnect()
            connection?.close()
        }
    }

    /** Frees the tab for good. */
    fun dispose() {
        disconnect()
        io.shutdown()
        term.close()
    }

    private suspend fun run() {
        _state.value = State.Connecting
        // Saved edits and a password saved at an earlier sign-in apply to a reconnect.
        val current = env.store.state.value.servers.firstOrNull { it.id == server.id } ?: server
        _target.value = current
        term.feedText("\u001b[2mConnecting to ${current.user}@${current.host}…\u001b[0m\r\n")
        val conn = SshConnection(env, current)
        connection = conn
        val end: String? = try {
            conn.connect(onBanner = { banner -> term.feedText(printable(banner).replace("\n", "\r\n")) })
            val (columns, rows) = withContext(Dispatchers.Main) { term.emulator?.let { it.mColumns to it.mRows } ?: (80 to 24) }
            val ch = conn.jsch.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", columns, rows, 0, 0)
            ch.setEnv("COLORTERM", "truecolor")
            val input = ch.inputStream
            val output = ch.outputStream
            ch.connect(CHANNEL_TIMEOUT_MS)
            channel = ch
            withContext(Dispatchers.Main) { term.setTransport(Transport(ch, output)) }
            _state.value = State.Connected
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                term.feed(buffer, 0, n)
            }
            if (conn.isConnected) null else "Connection lost"
        } catch (_: Cancelled) {
            "Cancelled"
        } catch (e: Exception) {
            describe(e)
        } finally {
            withContext(Dispatchers.Main) { term.setTransport(null) }
            channel?.disconnect()
            conn.close()
            channel = null
        }
        term.feedText("\r\n\u001b[2m[${printable(end ?: "Session ended")}]\u001b[0m\r\n")
        _state.value = State.Closed(end)
    }

    private inner class Transport(private val ch: ChannelShell, private val out: OutputStream) : TerminalSession.Transport {
        override fun output() = out

        override fun resize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
            io.execute { runCatching { ch.setPtySize(columns, rows, widthPixels, heightPixels) } }
        }
    }

    private companion object {
        const val CHANNEL_TIMEOUT_MS = 15_000

        /** A short reason for the end of a connection, shown in the terminal. */
        fun describe(e: Throwable): String {
            val cause = generateSequence(e) { it.cause }.toList()
            return when {
                // jsch's JSchAuthCancelException is not public.
                cause.any { it is Cancelled || it.javaClass.simpleName == "JSchAuthCancelException" } -> "Cancelled"
                cause.any { it is UnknownHostException } -> "Unknown host"
                cause.any { it is ConnectException } -> "Connection refused"
                cause.any { it is NoRouteToHostException } -> "No route to host"
                cause.any { it is SocketTimeoutException } -> "Connection timed out"
                cause.any { it is JSchChangedHostKeyException } -> "Host key changed — not trusted"
                cause.any { it is JSchUnknownHostKeyException } -> "Host key not trusted"
                cause.any { it is SignFailed } -> cause.first { it is SignFailed }.message.orEmpty()
                e.message?.contains("Auth fail", ignoreCase = true) == true -> "Sign-in failed"
                else -> e.message ?: e.javaClass.simpleName
            }
        }
    }
}
