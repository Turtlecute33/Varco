package app.varco.ssh

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.os.VibrationEffect
import android.os.VibratorManager
import app.varco.data.Server
import app.varco.data.Store
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The open terminal tabs. They live in the app process (kept alive by SessionService), not in the activity,
 * so a tab keeps its connection while the UI is closed or locked.
 */
class Sessions(private val context: Context, private val store: Store, private val env: SshEnv, private val scope: CoroutineScope) {
    private val _shells = MutableStateFlow<List<Shell>>(emptyList())
    val shells: StateFlow<List<Shell>> = _shells.asStateFlow()

    /** The tab on screen (id), or null. */
    val currentId = MutableStateFlow<String?>(null)

    /** The terminal view on screen; set by the terminal screen while it is shown. Main thread only. */
    var view: TerminalView? = null

    fun open(server: Server): Shell {
        val shell = Shell(server, TerminalSession(store.state.value.settings.scrollback, client), env, scope)
        _shells.update { it + shell }
        currentId.value = shell.id
        store.update { st -> st.copy(servers = st.servers.map { if (it.id == server.id) it.copy(lastUsed = System.currentTimeMillis()) else it }) }
        shell.start()
        return shell
    }

    fun close(shell: Shell) {
        shell.dispose()
        _shells.update { list -> list.filterNot { it === shell } }
        if (currentId.value == shell.id) currentId.value = _shells.value.lastOrNull()?.id
    }

    fun closeAll() = _shells.value.forEach(::close)

    fun byId(id: String?): Shell? = _shells.value.firstOrNull { it.id == id }

    private fun shellOf(session: TerminalSession) = _shells.value.firstOrNull { it.term === session }

    /** Terminal callbacks, on the main thread. Termux's log calls are dropped: Varco keeps no logs. */
    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            view?.takeIf { it.currentSession === changedSession }?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            shellOf(changedSession)?.title?.value = changedSession.title
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String, fromServer: Boolean) {
            if (fromServer && !store.state.value.settings.serverClipboard) return
            val clip = ClipData.newPlainText("Terminal", text)
            // Hides the text from the clipboard preview: terminal output often holds secrets.
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            session.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) {
            if (!store.state.value.settings.vibrateOnBell) return
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }

        override fun onColorsChanged(session: TerminalSession) {
            view?.takeIf { it.currentSession === session }?.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            view?.setTerminalCursorBlinkerState(state, true)
        }

        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }
}
