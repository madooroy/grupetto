package com.spop.poverlay.overlay

import android.view.View
import android.view.WindowManager.LayoutParams
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.ceil

@Suppress("LiftReturnOrAssignment")
class OverlayDialogViewModel(
    private val screenSize: Size,
    private val isMinimized : StateFlow<Boolean>,
    initialHorizontalOffset: Float = 0f,
    initialLocation: OverlayLocation = OverlayLocation.Bottom,
    savedSideOffset: Float? = null
    ) {
    companion object {
        // If the overlay is dragged within this range of pixels from the center of the screen
        // snap to the center of the screen instead
        private val HorizontalDragSnapRange = -20f..20f

        // Pushing a top/bottom bar this far past the left or right edge docks it there as a column
        // (a share of the screen width): a deliberate push, not a finger carrying on past the end of the track.
        const val SideDockPushFraction = .08f

        // Dragging a docked column this far away from its edge puts it back at the top or bottom.
        const val SideUndockDragFraction = .25f

        // A finger dragging the bar this close to the left or right edge of the screen (a share of its width) docks it.
        const val FingerEdgeFraction = .02f

        // Where a column first docks, below the screen's centre (a share of the screen height): between
        // Peloton's music card and high-five feed (upper left) and its target pills and metrics (bottom).
        const val DefaultSideOffsetFraction = .11f
    }

    private val initialSideOffset = savedSideOffset ?: (screenSize.height * DefaultSideOffsetFraction)

    val dialogOrigin = MutableStateFlow(
        if (initialLocation.isSide) Offset(0f, initialSideOffset) else Offset(initialHorizontalOffset, 0f)
    )
    private var horizontalDragOffset = if (initialLocation.isSide) 0f else initialHorizontalOffset
    // Position along the side edge, relative to the screen's vertical centre (kept while docked at the top
    // or bottom so the column comes back where the rider left it).
    var sideOffset = initialSideOffset
        private set
    private var edgePush = 0f
    private var sideAway = 0f
    private var overlayWidthPx = 0
    private var overlayHeightPx = 0
    // Defined as width to height
    val dialogSizeParams = MutableStateFlow(LayoutParams.WRAP_CONTENT to LayoutParams.WRAP_CONTENT)
    val minimizedDialogSizeParams = MutableStateFlow(LayoutParams.WRAP_CONTENT to LayoutParams.WRAP_CONTENT)
    val partialOverlayFlags = MutableStateFlow(0)
    val touchTargetVisiblity = MutableStateFlow(View.GONE)
    val dialogLocation = MutableStateFlow(initialLocation)
    val dialogGravity = MutableStateFlow(dialogLocation.value.gravity)


    val touchTargetHeight = MutableStateFlow(0f)
    // When overlay is hidden, an invisible touch target appears to accept touches:
    // - Touch target visibility is the opposite of the main view
    // - Overlay has FLAG_NOT_TOUCHABLE if it has started hiding
    // - Touch target height should match overlay height, plus margin for ease of use
    fun processHideProgress(hiddenHeight: Float, totalHeight: Float) {

        val remainingHeight = abs(totalHeight - (abs(hiddenHeight)))
        val isMinimizeDone = abs(hiddenHeight) > 0f

        if (isMinimizeDone) {
            touchTargetVisiblity.value = View.VISIBLE
            touchTargetHeight.value = remainingHeight + OverlayService.HiddenTouchTargetMarginPx
        } else {
            touchTargetVisiblity.value = View.GONE
            touchTargetHeight.value = 0f
        }


        partialOverlayFlags.value = if (isMinimizeDone) {
            LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            0
        }
    }

    // Accumulate horizontal drag deltas from the restored position.
    // - If the gesture is near the center of the screen, keep view at 0 (allows snapping to center)
    // - If the gesture is on screen, move the view to follow the gesture
    // - If the gesture would move the view offscreen, clamp it to screen bounds
    // - Kept pushing against the left or right edge, dock there as a column
    fun processHorizontalDrag(delta: Float) {
        if (dialogLocation.value.isSide) return
        val wanted = horizontalDragOffset + delta
        horizontalDragOffset = wanted.coerceIn(horizontalDragScreenRange)
        val x = if (horizontalDragOffset in HorizontalDragSnapRange) 0f else horizontalDragOffset
        updateOrigin(dialogOrigin, x = x)

        val pastEdge = wanted - horizontalDragOffset
        edgePush = when {
            pastEdge == 0f -> 0f
            edgePush == 0f || (pastEdge < 0f) == (edgePush < 0f) -> edgePush + pastEdge
            else -> pastEdge
        }
        // A bar as wide as the screen has no track to run along: every sideways move would count as a push.
        val hasTrack = horizontalDragScreenRange.endInclusive > 0f
        if (hasTrack && abs(edgePush) > screenSize.width * SideDockPushFraction) {
            moveTo(if (edgePush < 0f) OverlayLocation.Left else OverlayLocation.Right)
        }
    }

    // A docked column: slides along its edge, and goes back to the top or bottom
    // (whichever half it is in) when dragged well away from the edge.
    fun processSideDrag(deltaX: Float, deltaY: Float) {
        val location = dialogLocation.value
        if (!location.isSide) return
        sideOffset = (sideOffset + deltaY).coerceIn(sideDragScreenRange())
        updateOrigin(dialogOrigin, x = 0f, y = sideOffset)

        val away = if (location == OverlayLocation.Left) deltaX else -deltaX
        sideAway = (sideAway + away).coerceAtLeast(0f)
        if (sideAway > screenSize.width * SideUndockDragFraction) {
            val bar = if (sideOffset < 0f) OverlayLocation.Top else OverlayLocation.Bottom
            // The bar arrives centred under the finger (about half a column in from the edge, plus the drag away),
            // so the rest of its track is still ahead for a drag that carries on to the other side.
            val fingerX = overlayWidthPx / 2f + sideAway
            val fromCentre = if (location == OverlayLocation.Left) fingerX - screenSize.width / 2f
            else screenSize.width / 2f - fingerX
            moveTo(bar)
            horizontalDragOffset = fromCentre.coerceIn(horizontalDragScreenRange)
            updateOrigin(dialogOrigin, x = horizontalDragOffset, y = 0f)
        }
    }

    /**
     * A bar being dragged with the finger at [fingerScreenX] (px from the screen's left): a finger at the very edge of
     * the screen docks it there, wherever the bar has got to on its track (the bar follows the finger only partly
     * while it slides, so on a drag across the screen it may not reach the end of its track before the finger does).
     */
    fun processFingerAtEdge(fingerScreenX: Float) {
        if (dialogLocation.value.isSide || horizontalDragScreenRange.endInclusive <= 0f) return
        val zone = screenSize.width * FingerEdgeFraction
        when {
            fingerScreenX <= zone -> moveTo(OverlayLocation.Left)
            fingerScreenX >= screenSize.width - zone -> moveTo(OverlayLocation.Right)
        }
    }

    fun onDragFinished() {
        edgePush = 0f
        sideAway = 0f
    }

    private fun moveTo(location: OverlayLocation) {
        edgePush = 0f
        sideAway = 0f   // a new shape starts its own count (a column docked after an undock must not undock at once)
        dialogLocation.value = location
        dialogGravity.value = location.gravity
        if (location.isSide) {
            sideOffset = sideOffset.coerceIn(sideDragScreenRange())
            updateOrigin(dialogOrigin, x = 0f, y = sideOffset)
        }
    }

    private fun sideDragScreenRange(): ClosedFloatingPointRange<Float> {
        val range = ((screenSize.height - overlayHeightPx) / 2f).coerceAtLeast(0f)
        return -range..range
    }

    fun onOverlayLayout(size : IntSize){
        val (_, currentHeight) = dialogSizeParams.value
        dialogSizeParams.value = size.width to currentHeight
        overlayHeightPx = size.height
        overlayWidthPx = size.width
        if (dialogLocation.value.isSide) {
            // Keep a docked column on screen when it grows (expanded) or the display changes.
            sideOffset = sideOffset.coerceIn(sideDragScreenRange())
            updateOrigin(dialogOrigin, x = 0f, y = sideOffset)
        } else {
            updateHorizontalDragScreenRange()
        }
    }

    fun onTimerOverlayLayout(size : IntSize){
        val (_, currentHeight) = dialogSizeParams.value
        minimizedDialogSizeParams.value = size.width to currentHeight
        updateHorizontalDragScreenRange()
    }
    private var horizontalDragScreenRange = calculateHorizontalDragScreenRange(0)

    private fun updateHorizontalDragScreenRange() {
        if (dialogLocation.value.isSide) return
        val width = maxOf(dialogSizeParams.value.first, minimizedDialogSizeParams.value.first, 0)
        horizontalDragScreenRange = calculateHorizontalDragScreenRange(width)
        horizontalDragOffset = horizontalDragOffset.coerceIn(horizontalDragScreenRange)
        // Keep a restored position reachable if the display or overlay size has changed.
        updateOrigin(dialogOrigin, x = dialogOrigin.value.x.coerceIn(horizontalDragScreenRange))
    }

    // Takes the current vertical progress of a drag and returns a new progress
    // - Reset the progress to 0 and move the view once drag is halfway across screen
    // - Keep the current progress otherwise
    fun processVerticalDrag(distance: Float): Float {
        if (dialogLocation.value.isSide) return 0f
        if (abs(distance) > screenSize.height * OverlayService.VerticalMoveDragThreshold) {
            moveTo(verticalToggleOverlayLocation(dialogLocation.value))
            return 0f
        } else {
            return distance // Continue drag gesture
        }
    }

    private fun updateOrigin(
        origin: MutableStateFlow<Offset>,
        x: Float = origin.value.x,
        y: Float = origin.value.y
    ) {
        origin.value = origin.value.copy(x = x, y = y)
    }


    private fun calculateHorizontalDragScreenRange(overlayWidthPx : Int): ClosedFloatingPointRange<Float> {
        val dragRange = ceil((screenSize.width - overlayWidthPx) / 2f).toInt().coerceAtLeast(0)
        return -dragRange.toFloat()..dragRange.toFloat()
    }

    private fun verticalToggleOverlayLocation(location: OverlayLocation) =
        if (location == OverlayLocation.Bottom) {
            OverlayLocation.Top
        } else {
            OverlayLocation.Bottom
        }
}
