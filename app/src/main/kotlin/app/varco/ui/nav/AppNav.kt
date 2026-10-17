package app.varco.ui.nav

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.varco.ui.files.FilesScreen
import app.varco.ui.home.HomeScreen
import app.varco.ui.importer.ImportScreen
import app.varco.ui.keys.KeyScreen
import app.varco.ui.keys.KeysScreen
import app.varco.ui.servers.EditServerScreen
import app.varco.ui.settings.SettingsScreen
import app.varco.ui.snippets.SnippetsScreen
import app.varco.ui.terminal.TerminalScreen

/**
 * Shows the top route of [nav] with navigation3. System back and the predictive back gesture pop the stack.
 * A `viewModel()` inside a screen is scoped to its back-stack entry and cleared when the entry is popped.
 */
@Composable
fun AppNav(nav: Navigator, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    if (nav.finished) LaunchedEffect(Unit) { activity?.finish() }

    val forward = sharedAxis<Scene<Route>>(forward = true)
    val backward = sharedAxis<Scene<Route>>(forward = false)
    NavDisplay(
        backStack = nav.backStack,
        modifier = modifier,
        onBack = nav::back,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = forward,
        popTransitionSpec = backward,
        predictivePopTransitionSpec = { backward() },
        entryProvider = entryProvider {
            entry<Route.Home> { HomeScreen(nav) }
            entry<Route.Terminal> { TerminalScreen(nav) }
            entry<Route.EditServer> { EditServerScreen(it.id, nav) }
            entry<Route.Keys> { KeysScreen(nav) }
            entry<Route.Key> { KeyScreen(it.id, nav) }
            entry<Route.Snippets> { SnippetsScreen(nav) }
            entry<Route.Settings> { SettingsScreen(nav) }
            entry<Route.Import> { ImportScreen(nav) }
            entry<Route.Files> { FilesScreen(it.serverId, nav) }
        },
    )
}

/** A transition spec of AnimatedContent or NavDisplay. */
internal typealias Transition<S> = AnimatedContentTransitionScope<S>.() -> ContentTransform

/** Material shared-axis X with the theme's expressive motion: a short slide plus a cross-fade. */
@Composable
internal fun <S> sharedAxis(forward: Boolean): Transition<S> {
    val motion = MaterialTheme.motionScheme
    val slide = motion.defaultSpatialSpec<IntOffset>()
    val fadeIn = motion.defaultEffectsSpec<Float>()
    val fadeOut = motion.fastEffectsSpec<Float>()
    val sign = if (forward == (LocalLayoutDirection.current == LayoutDirection.Ltr)) 1 else -1
    val offset = sign * with(LocalDensity.current) { 48.dp.roundToPx() }
    return {
        (slideInHorizontally(slide) { offset } + fadeIn(fadeIn)) togetherWith
            (slideOutHorizontally(slide) { -offset } + fadeOut(fadeOut))
    }
}
