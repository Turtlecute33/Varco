package app.varco.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Drag to reorder the rows of a LazyColumn. Only items with the content type [ROW] move, so the list can also
 * hold headers and other rows. [onMove] gets the keys of the dragged row and of the row whose place it takes;
 * the caller changes its list, and the dragged row stays under the finger. Past an edge, the list scrolls.
 */
@Stable
class Reorder internal constructor(
    private val list: LazyListState,
    private val view: View,
    private val scope: CoroutineScope,
    private val spec: AnimationSpec<Float>,
    internal val placement: FiniteAnimationSpec<IntOffset>,
    /** The background of the lifted row. */
    internal val color: Color,
    private val onMove: (from: Any, to: Any) -> Unit,
) {
    /** The key of the lifted row: the dragged row, or the dropped row on its way back to its place. */
    var key by mutableStateOf<Any?>(null)
        private set
    internal var dragging by mutableStateOf(false)
        private set

    /** The top of the lifted row on the screen is [start] + [drag], in list coordinates. */
    private var start = 0f
    private var drag by mutableFloatStateOf(0f)

    /** 1 while the row follows the finger, then 0 when the dropped row is in its place. */
    private var lift by mutableFloatStateOf(1f)
    private var settle: Job? = null

    /** The index of the dragged row before the last move; no move until the list shows the last one. */
    private var moving = -1

    private fun item(k: Any?) = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }

    /** How far the lifted row is drawn from its place in the list. Read it only in a layer block. */
    val offset: Float get() = item(key)?.let { (start + drag - it.offset) * lift } ?: 0f

    internal fun start(k: Any) {
        val row = item(k) ?: return
        settle?.cancel()
        // A row that is still on its way back starts from where it is drawn.
        start = row.offset + if (k == key) offset else 0f
        drag = 0f
        lift = 1f
        moving = -1
        key = k
        dragging = true
        view.performHapticFeedback(HapticFeedbackConstants.DRAG_START)
    }

    internal fun drag(dy: Float) {
        if (!dragging) return
        drag += dy
        swap()
    }

    internal fun stop() {
        if (!dragging) return
        dragging = false
        view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        settle = scope.launch {
            animate(1f, 0f, animationSpec = spec) { v, _ -> lift = v }
            key = null
        }
    }

    /**
     * Moves the dragged row when its bottom edge goes below the centre of a row under it, or its top edge goes
     * above the centre of a row over it. The edge rule (not the centre of the dragged row) keeps rows of
     * different heights from swapping back and forth.
     */
    private fun swap() {
        val row = item(key) ?: return
        if (row.index == moving) return
        val top = start + drag
        val bottom = top + row.size
        val rows = list.layoutInfo.visibleItemsInfo
        val target = rows.lastOrNull { it.contentType == ROW && it.index > row.index && bottom > it.offset + it.size / 2f }
            ?: rows.firstOrNull { it.contentType == ROW && it.index < row.index && top < it.offset + it.size / 2f }
            ?: return
        // The list keeps its first visible item in place. Without this, a move of that item scrolls the list.
        if (row.index == list.firstVisibleItemIndex || target.index == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        moving = row.index
        onMove(row.key, target.key)
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    }

    /** Scrolls while the dragged row is past the top or the bottom edge, faster the farther it is past. */
    internal suspend fun autoScroll() {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val step = speed() * (now - last) / 1e9f
            last = now
            if (step != 0f) {
                list.scrollBy(step)
                swap()
            }
        }
    }

    /** Pixels per second. The content padding is under the bars, so it counts as past the edge. */
    private fun speed(): Float {
        val row = item(key) ?: return 0f
        val info = list.layoutInfo
        val top = start + drag
        val end = (info.viewportEndOffset - info.afterContentPadding).toFloat()
        val past = when {
            top < 0f -> top
            top + row.size > end -> top + row.size - end
            else -> return 0f
        }
        return past.coerceIn(-row.size.toFloat(), row.size.toFloat()) * ROWS_PER_SECOND
    }

    companion object {
        /** The content type of the rows that can move. */
        const val ROW = "reorder-row"
        private const val ROWS_PER_SECOND = 8f
    }
}

@Composable
fun rememberReorder(list: LazyListState, onMove: (from: Any, to: Any) -> Unit): Reorder {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    val move by rememberUpdatedState(onMove)
    val reorder = remember(list, view, scope, color) {
        Reorder(list, view, scope, motion.fastSpatialSpec(), motion.defaultSpatialSpec(), color) { from, to -> move(from, to) }
    }
    LaunchedEffect(reorder) { snapshotFlow { reorder.dragging }.collectLatest { if (it) reorder.autoScroll() } }
    return reorder
}

/** The modifier of the row [key] in [item]: lifted while dragged, and with a placement animation otherwise. */
fun Modifier.reorderRow(item: LazyItemScope, reorder: Reorder, key: Any): Modifier =
    if (reorder.key == key) {
        zIndex(1f)
            .graphicsLayer {
                translationY = reorder.offset
                shadowElevation = 6.dp.toPx()
            }
            .background(reorder.color)
    } else {
        with(item) { animateItem(placementSpec = reorder.placement) }
    }

/**
 * The drag handle of the row [key], with a 48 dp touch target. The drag starts on touch, and the handle takes
 * all its touches, so a touch on it never opens the row or scrolls the list.
 */
@Composable
fun DragHandle(reorder: Reorder, key: Any) {
    Box(
        modifier = Modifier.size(48.dp).pointerInput(reorder, key) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                reorder.start(key)
                try {
                    verticalDrag(down.id) { change ->
                        reorder.drag(change.positionChange().y)
                        change.consume()
                    }
                } finally {
                    reorder.stop()
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.DragIndicator, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
