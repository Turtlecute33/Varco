package app.varco.ui.terminal

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.varco.term.Scheme
import app.varco.ui.components.Icons
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the extra-keys bar sends. */
interface KeySink {
    /** A special key (KeyEvent code), with the modifiers of the bar. */
    fun key(keyCode: Int)

    /** A character, with the modifiers of the bar. */
    fun char(codePoint: Int)
}

/**
 * Two rows above the keyboard, in the terminal colours:
 * [● server ▾] ESC TAB CTRL ALT
 * ← ↓ ↑ → - / | ⚡
 * Arrows repeat while held. A long press on "-", "/" and "|" sends "_", "\" and "&". A tap on CTRL or ALT
 * applies it to the next key; a long press locks it.
 */
@Composable
fun ExtraKeys(
    scheme: Scheme,
    modifiers: Modifiers,
    sink: KeySink,
    chipLabel: String,
    chipDot: Color,
    onChip: () -> Unit,
    onSnippets: () -> Unit,
) {
    val bg = Color(scheme.background)
    val fg = Color(scheme.foreground)
    val keyBg = lerp(bg, fg, 0.08f)
    val accent = Color(scheme.ansi[4])
    Column(
        Modifier
            .fillMaxWidth()
            .background(lerp(bg, fg, 0.03f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip(chipLabel, chipDot, fg, keyBg, onChip, Modifier.weight(2.2f))
            Key("ESC", fg, keyBg, onTap = { sink.key(KeyEvent.KEYCODE_ESCAPE) })
            Key("TAB", fg, keyBg, onTap = { sink.key(KeyEvent.KEYCODE_TAB) })
            Mod("CTRL", modifiers.ctrl, fg, keyBg, accent, bg)
            Mod("ALT", modifiers.alt, fg, keyBg, accent, bg)
        }
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Key("←", fg, keyBg, repeat = true, onTap = { sink.key(KeyEvent.KEYCODE_DPAD_LEFT) })
            Key("↓", fg, keyBg, repeat = true, onTap = { sink.key(KeyEvent.KEYCODE_DPAD_DOWN) })
            Key("↑", fg, keyBg, repeat = true, onTap = { sink.key(KeyEvent.KEYCODE_DPAD_UP) })
            Key("→", fg, keyBg, repeat = true, onTap = { sink.key(KeyEvent.KEYCODE_DPAD_RIGHT) })
            Key("-", fg, keyBg, alt = "_", onTap = { sink.char('-'.code) }, onLong = { sink.char('_'.code) })
            Key("/", fg, keyBg, alt = "\\", onTap = { sink.char('/'.code) }, onLong = { sink.char('\\'.code) })
            Key("|", fg, keyBg, alt = "&", onTap = { sink.char('|'.code) }, onLong = { sink.char('&'.code) })
            IconKey(fg, keyBg, onSnippets)
        }
    }
}

private val KeyShape = RoundedCornerShape(10.dp)

@Composable
private fun RowScope.Key(
    label: String,
    fg: Color,
    bg: Color,
    alt: String? = null,
    repeat: Boolean = false,
    onTap: () -> Unit,
    onLong: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(KeyShape)
            .background(bg)
            .pointerInput(repeat, onLong != null) {
                if (repeat) {
                    // Sends once on press, then repeats after 400 ms while the finger stays down.
                    detectTapGestures(onPress = {
                        haptic()
                        tap()
                        coroutineScope {
                            val repeater = launch {
                                delay(400)
                                while (true) {
                                    tap()
                                    delay(50)
                                }
                            }
                            tryAwaitRelease()
                            repeater.cancel()
                        }
                    })
                } else {
                    detectTapGestures(
                        onTap = { haptic(); tap() },
                        onLongPress = if (onLong != null) { _ -> haptic(); long?.invoke() } else null,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (alt != null) {
            Text(
                alt,
                color = fg.copy(alpha = 0.45f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 5.dp),
            )
        }
    }
}

@Composable
private fun RowScope.Mod(label: String, key: ModKey, fg: Color, bg: Color, accent: Color, onAccent: Color) {
    val view = LocalView.current
    val (keyBg, keyFg) = when (key.mode) {
        ModKey.Mode.OFF -> bg to fg
        ModKey.Mode.ONCE -> lerp(bg, accent, 0.45f) to fg
        ModKey.Mode.LOCKED -> accent to onAccent
    }
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(KeyShape)
            .background(keyBg)
            .pointerInput(key) {
                detectTapGestures(
                    onTap = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); key.tap() },
                    onLongPress = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); key.lock() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = keyFg, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RowScope.IconKey(fg: Color, bg: Color, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(KeyShape)
            .background(bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Bolt, contentDescription = "Snippets", tint = fg, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Chip(label: String, dot: Color, fg: Color, bg: Color, onTap: () -> Unit, modifier: Modifier) {
    val tap by rememberUpdatedState(onTap)
    Row(
        modifier
            .fillMaxHeight()
            .widthIn(min = 64.dp)
            .clip(KeyShape)
            .background(bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.size(8.dp))
        Text(label, color = fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("▾", color = fg.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}
