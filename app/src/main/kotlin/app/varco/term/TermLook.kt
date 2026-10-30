package app.varco.term

import android.content.Context
import android.graphics.Typeface
import app.varco.R
import app.varco.data.Settings
import app.varco.importer.Importer
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle

/** Colours and font of the terminal engine. */
object TermLook {
    /** The last scheme that [apply] set. The same scheme again keeps the colours that the server set (OSC 4, 10, 11, 12). */
    private var applied: Scheme? = null

    /** Sets [scheme] as the default colours of every terminal (the engine has one shared scheme). Main thread. */
    fun apply(scheme: Scheme, sessions: List<TerminalSession>) {
        if (scheme === applied) return
        applied = scheme
        val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
        scheme.ansi.copyInto(colors, 0)
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = scheme.foreground
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = scheme.background
        colors[TextStyle.COLOR_INDEX_CURSOR] = scheme.cursor
        sessions.forEach { it.emulator?.mColors?.reset() }
    }

    /** The imported font when the settings select it, else JetBrains Mono. Reads a file: not on the main thread. */
    fun typeface(context: Context, settings: Settings): Typeface {
        val file = Importer.fontFile(context)
        if (settings.font == Importer.FONT_CUSTOM && file.exists()) {
            runCatching { Typeface.createFromFile(file) }.getOrNull()?.let { return it }
        }
        return context.resources.getFont(R.font.jetbrains_mono)
    }
}
