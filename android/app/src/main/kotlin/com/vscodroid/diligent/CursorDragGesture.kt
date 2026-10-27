package com.vscodroid.diligent

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Mobile view's press-and-drag cursor, with no Android types in it: a finger
 * held still on the code for [HOLD_MS] turns into a trackpad, and dragging
 * then moves the text cursor, one character per [stepXPx] sideways and one
 * line per [stepYPx] up or down, until the finger lifts.
 *
 * Until the hold fires every touch belongs to the editor page, so taps,
 * scrolling swipes, pinches and the editor's own selection work as before:
 * moving past [slopPx] or a second finger before the hold gives the touch up
 * for good. The caller (the activity's touch dispatch, through
 * [DiligentController]) runs the timer, sends the arrows through the extra key
 * row's key injector, and cancels the page's touch when the hold fires.
 *
 * Coordinates are pixels, for the one finger the gesture follows.
 */
class CursorDragGesture(
    private val slopPx: Float,
    private val stepXPx: Float,
    private val stepYPx: Float,
) {
    init {
        require(slopPx >= 0f && stepXPx > 0f && stepYPx > 0f) { "steps must be positive" }
    }

    enum class State {
        /** No finger, or a touch that has been given to the page. */
        Idle,
        /** A finger is down and still; the hold has not fired yet. */
        Waiting,
        /** The hold fired: moves are cursor steps. */
        Dragging,
    }

    var state: State = State.Idle
        private set

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pendingX = 0f
    private var pendingY = 0f

    /** A first finger touched the code. The caller starts the hold timer. */
    fun down(x: Float, y: Float) {
        state = State.Waiting
        downX = x
        downY = y
        lastX = x
        lastY = y
        pendingX = 0f
        pendingY = 0f
    }

    /**
     * The followed finger moved. While waiting, a move past the slop is a
     * scroll or a selection and the touch goes to the page (answers empty).
     * While dragging, answers the arrows the move earned, sideways first.
     */
    fun move(x: Float, y: Float): List<String> {
        when (state) {
            State.Idle -> return emptyList()
            State.Waiting -> {
                if (hypot(x - downX, y - downY) > slopPx) state = State.Idle
                lastX = x
                lastY = y
                return emptyList()
            }
            State.Dragging -> Unit
        }
        pendingX += x - lastX
        pendingY += y - lastY
        lastX = x
        lastY = y
        val keys = ArrayList<String>()
        while (abs(pendingX) >= stepXPx) {
            keys.add(if (pendingX > 0) ARROW_RIGHT else ARROW_LEFT)
            pendingX -= sign(pendingX) * stepXPx
        }
        while (abs(pendingY) >= stepYPx) {
            keys.add(if (pendingY > 0) ARROW_DOWN else ARROW_UP)
            pendingY -= sign(pendingY) * stepYPx
        }
        return keys
    }

    /** Another finger came down: a pinch, unless the drag already started. */
    fun otherPointerDown() {
        if (state == State.Waiting) state = State.Idle
    }

    /**
     * The hold timer ran out. Answers true when that started a drag, which is
     * when the caller cancels the page's touch and gives haptic feedback.
     */
    fun holdElapsed(): Boolean {
        if (state != State.Waiting) return false
        state = State.Dragging
        return true
    }

    /**
     * The followed finger lifted, or the touch was cancelled. Answers whether a
     * drag was running, in which case the page must not see the lift.
     */
    fun end(): Boolean {
        val wasDragging = state == State.Dragging
        state = State.Idle
        return wasDragging
    }

    companion object {
        /** How long a finger rests still before it moves the cursor. Shorter than Android's long press (400 ms or more). */
        const val HOLD_MS = 300L

        /** Sideways travel per character, and vertical travel per line, in dp. */
        const val STEP_X_DP = 10f
        const val STEP_Y_DP = 20f

        // DOM key names, as the extra key row's trackpad sends them (keyboard/KeyMapping.kt).
        const val ARROW_LEFT = "ArrowLeft"
        const val ARROW_RIGHT = "ArrowRight"
        const val ARROW_UP = "ArrowUp"
        const val ARROW_DOWN = "ArrowDown"
    }
}
