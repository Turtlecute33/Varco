package app.varco.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.varco.BuildConfig
import app.varco.app
import app.varco.data.Settings
import app.varco.data.Settings.Companion.MAX_FONT
import app.varco.data.Settings.Companion.MIN_FONT
import app.varco.importer.Importer
import app.varco.term.Scheme
import app.varco.term.Schemes
import app.varco.ui.components.Bar
import app.varco.ui.components.ConfirmDialog
import app.varco.ui.components.Icons
import app.varco.ui.components.ListRow
import app.varco.ui.components.SectionHeader
import app.varco.ui.components.SwitchRow
import app.varco.ui.nav.Navigator
import app.varco.ui.nav.Route
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val state by app.store.state.collectAsStateWithLifecycle()
    val s = state.settings
    fun set(change: (Settings) -> Settings) = app.store.update { it.copy(settings = change(it.settings)) }
    var removing by remember { mutableStateOf<Scheme?>(null) }
    var licences by remember { mutableStateOf(false) }
    val current = Schemes.byId(s.schemeId, state.schemes)

    Scaffold(topBar = { Bar("Settings", onBack = nav::back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Theme")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.schemes + Schemes.all, key = { it.id }) { scheme ->
                    SchemeCard(
                        scheme = scheme,
                        selected = scheme.id == current.id,
                        onClick = { set { it.copy(schemeId = scheme.id) } },
                        onLongClick = if (scheme.id.startsWith("custom:")) ({ removing = scheme }) else null,
                    )
                }
            }
            ListRow(title = "Font size", subtitle = "${s.fontSize} sp · pinch the terminal to change it too") {}
            // One step for each sp, in the same range as pinch zoom.
            val slider = rememberSliderState(s.fontSize.toFloat(), MAX_FONT - MIN_FONT - 1, MIN_FONT.toFloat()..MAX_FONT.toFloat())
            // Pinch zoom in the terminal changes the size too.
            LaunchedEffect(s.fontSize) { slider.value = s.fontSize.toFloat() }
            Slider(
                state = slider,
                onValueChange = { v ->
                    slider.value = v
                    set { it.copy(fontSize = v.roundToInt()) }
                },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (Importer.fontFile(context).exists()) {
                ListRow(title = "JetBrains Mono", onClick = { set { it.copy(font = "jetbrains-mono") } }) {
                    RadioButton(selected = s.font != Importer.FONT_CUSTOM, onClick = { set { it.copy(font = "jetbrains-mono") } })
                }
                ListRow(title = "Imported font", onClick = { set { it.copy(font = Importer.FONT_CUSTOM) } }) {
                    RadioButton(selected = s.font == Importer.FONT_CUSTOM, onClick = { set { it.copy(font = Importer.FONT_CUSTOM) } })
                }
            }

            SectionHeader("Terminal")
            ListRow(title = "Scrollback", subtitle = "${s.scrollback} lines per session (for new sessions)") {}
            Row(Modifier.padding(horizontal = 12.dp)) {
                listOf(1000, 5000, 20000).forEach { lines ->
                    Choice("%,d".format(lines), s.scrollback == lines) { set { it.copy(scrollback = lines) } }
                }
            }
            SwitchRow("Keep screen on", s.keepScreenOn, { v -> set { it.copy(keepScreenOn = v) } }, "While the terminal is shown")
            SwitchRow("Vibrate on bell", s.vibrateOnBell, { v -> set { it.copy(vibrateOnBell = v) } })
            SwitchRow(
                "Servers can set the clipboard",
                s.serverClipboard,
                { v -> set { it.copy(serverClipboard = v) } },
                "OSC 52, for example copy in tmux or vim. Off: a server cannot put text in your clipboard.",
            )

            SectionHeader("Security")
            SwitchRow("App lock", s.appLock, { v -> set { it.copy(appLock = v) } }, "Fingerprint or screen lock to open Varco. Sessions stay connected.")
            if (s.appLock) {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    listOf(0 to "At once", 60 to "1 min", 300 to "5 min", 900 to "15 min").forEach { (seconds, label) ->
                        Choice(label, s.lockAfter == seconds) { set { it.copy(lockAfter = seconds) } }
                    }
                }
            }
            SwitchRow(
                "Block screenshots",
                s.secureWindow,
                { v -> set { it.copy(secureWindow = v) } },
                "Also hides Varco in the Recents screen and from screen recording",
            )

            SectionHeader("Data")
            ListRow(
                title = "Import from Termux",
                subtitle = "Keys, ~/.ssh/config, known_hosts, colors and font",
                icon = Icons.Import,
                onClick = { nav.go(Route.Import) },
            )

            SectionHeader("About")
            ListRow(title = "Varco ${BuildConfig.VERSION_NAME}", subtitle = "No analytics, no network use except SSH. GPL-3.0.", icon = Icons.Info)
            ListRow(title = "Open-source licences", onClick = { licences = true })
            Spacer(Modifier.height(32.dp))
        }
    }

    removing?.let { scheme ->
        ConfirmDialog(
            title = "Remove ${scheme.name}?",
            text = "The imported colour scheme is removed.",
            confirm = "Remove",
            onConfirm = {
                app.store.update { st ->
                    st.copy(
                        schemes = st.schemes.filterNot { it.id == scheme.id },
                        settings = if (st.settings.schemeId == scheme.id) st.settings.copy(schemeId = null) else st.settings,
                    )
                }
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
    if (licences) {
        AlertDialog(
            onDismissRequest = { licences = false },
            title = { Text("Open-source licences") },
            text = { Text(LICENCES, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { licences = false }) { Text("Close") } },
        )
    }
}

private const val LICENCES = """Terminal engine: Termux terminal-emulator and terminal-view (GPLv3; parts Apache-2.0 from jackpal's Terminal Emulator for Android). Changed for SSH.

SSH: JSch by mwiede (BSD-3-Clause), with jBCrypt (ISC).

Cryptography: Bouncy Castle (MIT-style Bouncy Castle Licence).

YubiKey: Yubico YubiKit for Android (Apache-2.0).

Font: JetBrains Mono (SIL Open Font License 1.1).

Icons: Material Symbols (Apache-2.0).

AndroidX, Jetpack Compose, Kotlin (Apache-2.0)."""

/** A radio option; the whole row (button and label) is the touch target. */
@Composable
private fun RowScope.Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.weight(1f).selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
private fun SchemeCard(scheme: Scheme, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val bg = Color(scheme.background)
    val fg = Color(scheme.foreground)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(16.dp))
            // TalkBack: a radio option that reads "selected", and names the long press of an imported scheme.
            .semantics { this.selected = selected }
            .combinedClickable(
                role = Role.RadioButton,
                onLongClickLabel = if (onLongClick != null) "Remove" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                buildString { append("~ ❯ ls") },
                color = fg,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                // Only a preview: TalkBack reads the scheme name.
                modifier = Modifier.clearAndSetSemantics {},
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..6).forEach { i -> Box(Modifier.size(12.dp).background(Color(scheme.ansi[i]), CircleShape)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(scheme.name, color = fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
