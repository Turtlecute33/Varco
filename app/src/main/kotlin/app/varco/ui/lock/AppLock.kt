package app.varco.ui.lock

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The optional app lock. When on, the UI is locked at process start and after [timeoutSeconds] in the
 * background. It hides only the UI; open SSH sessions stay connected. MainActivity reports [onForeground]
 * and [onBackground]; [LockScreen] calls [unlock].
 */
class AppLock(
    private val isEnabled: () -> Boolean,
    private val timeoutSeconds: () -> Int,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val state = MutableStateFlow(false)

    /** elapsedRealtime when the unlocked UI left the screen; null while visible or locked. */
    private var backgroundAt: Long? = null

    /** True while the UI must stay covered. */
    val locked: StateFlow<Boolean> = state.asStateFlow()

    /** The settings are read: lock at start when the lock is on. */
    fun onLoaded() {
        state.value = isEnabled()
    }

    /** The UI left the screen (Activity.onStop, but not for a configuration change). */
    fun onBackground() {
        if (!state.value) backgroundAt = clock()
    }

    /** The UI comes back (Activity.onStart): lock when the timeout has passed. */
    fun onForeground() {
        val since = backgroundAt
        backgroundAt = null
        state.value = when {
            !isEnabled() -> false
            since == null -> state.value
            else -> clock() - since >= timeoutSeconds().coerceAtLeast(0) * 1000L
        }
    }

    /** The user passed the system prompt. */
    fun unlock() {
        backgroundAt = null
        state.value = false
    }
}
