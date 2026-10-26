package app.varco

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.varco.term.Schemes
import app.varco.ui.components.SensitiveClip
import app.varco.ui.lock.LockScreen
import app.varco.ui.nav.AppNav
import app.varco.ui.nav.Navigator
import app.varco.ui.prompts.PromptDialogs
import app.varco.ui.theme.VarcoTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The only activity. Keeps the window private (setting), and covers the UI with the app lock when it is on. */
class MainActivity : ComponentActivity() {
    private val nav: Navigator by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepSplashUntilReady()
        lifecycleScope.launch {
            app.store.state.map { it.settings.secureWindow }.distinctUntilChanged().collect(::applyWindowPrivacy)
        }

        setContent {
            val state by app.store.state.collectAsStateWithLifecycle()
            val locked by app.appLock.locked.collectAsStateWithLifecycle()
            val scheme = Schemes.byId(state.settings.schemeId, state.schemes)
            LaunchedEffect(scheme) {
                // Light or dark system bar icons, after the scheme's background.
                val bars = if (scheme.isDark) SystemBarStyle.dark(scheme.background) else SystemBarStyle.light(scheme.background, scheme.foreground)
                enableEdgeToEdge(bars, bars)
                window.decorView.setBackgroundColor(scheme.background)
            }
            VarcoTheme(scheme) {
                // Keeps the screens' saved state while the lock removes them from the composition.
                val screens = rememberSaveableStateHolder()
                Surface(Modifier.fillMaxSize()) {
                    if (locked) {
                        LockScreen(onUnlocked = app.appLock::unlock)
                    } else {
                        screens.SaveableStateProvider("screens") { AppNav(nav) }
                        PromptDialogs()
                    }
                }
            }
        }
    }

    override fun onStart() {
        app.appLock.onForeground()
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) app.appLock.onBackground()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Only the focused app can read the clipboard: a secret copied before the app went to the
        // background is cleared now, if its minute is over.
        if (hasFocus) SensitiveClip.clearIfDue(this)
    }

    /** Blocks screenshots, screen recording and the Recents preview: terminals show secrets. */
    private fun applyWindowPrivacy(secure: Boolean) {
        setRecentsScreenshotEnabled(!secure)
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Holds the system splash screen until the store is read, so that no empty list shows. */
    private fun keepSplashUntilReady() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!app.ready.value) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }
}
