package app.varco.ui.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient

/** A modifier key of the extra-keys bar: off, on for the next key, or locked on. */
class ModKey {
    enum class Mode { OFF, ONCE, LOCKED }

    var mode by mutableStateOf(Mode.OFF)

    /** Reads the key for one key press; a ONCE key turns off. */
    fun consume(): Boolean {
        val on = mode != Mode.OFF
        if (mode == Mode.ONCE) mode = Mode.OFF
        return on
    }

    fun tap() {
        mode = if (mode == Mode.OFF) Mode.ONCE else Mode.OFF
    }

    fun lock() {
        mode = if (mode == Mode.LOCKED) Mode.OFF else Mode.LOCKED
    }
}

class Modifiers {
    val ctrl = ModKey()
    val alt = ModKey()

    /** KeyHandler modifier bits for one special key, consumed like a key press. */
    fun keyMod(): Int = (if (ctrl.consume()) KeyHandler.KEYMOD_CTRL else 0) or (if (alt.consume()) KeyHandler.KEYMOD_ALT else 0)
}

/**
 * The terminal view's settings and callbacks. Character input goes through the keyboard without suggestions
 * (Termux's "enforce char based input"), so the keyboard sends each key at once and learns nothing.
 */
class TermClient(
    private val modifiers: Modifiers,
    private val onTap: () -> Unit,
    private val onFontStep: (Int) -> Unit,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            onFontStep(if (scale > 1f) 1 else -1)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) = onTap()
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = modifiers.ctrl.consume()
    override fun readAltKey() = modifiers.alt.consume()
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
