package app.varco.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** The back stack. A ViewModel, so it survives configuration changes. Every route is in it at most once. */
class Navigator : ViewModel() {
    private val stack = mutableStateListOf<Route>(Route.Home)

    val backStack: List<Route> get() = stack

    val current: Route get() = stack.last()

    /** True after [back] on the last route: the activity should finish. */
    var finished by mutableStateOf(false)
        private set

    /** Opens [route]. When it is in the stack already, goes back to it instead. */
    fun go(route: Route) {
        val index = stack.indexOf(route)
        if (index < 0) stack += route else stack.removeRange(index + 1, stack.size)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finished = true
    }

    /** Replaces the top screen with [route]. */
    fun replaceTop(route: Route) {
        stack.removeAt(stack.lastIndex)
        go(route)
    }
}
