package app.varco.ui.nav

/** Screens. The back stack lives in [Navigator] (a ViewModel); it is not saved across process death. */
sealed interface Route {
    data object Home : Route

    /** The terminal; it shows the current tab of [app.varco.ssh.Sessions]. */
    data object Terminal : Route

    /** [id] null = a new server. */
    data class EditServer(val id: String?) : Route
    data object Keys : Route
    data class Key(val id: String) : Route
    data object Snippets : Route
    data object Settings : Route
    data object Import : Route
    data class Files(val serverId: String) : Route
}
