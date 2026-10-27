package com.vscodroid.diligent

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView

/**
 * The touch plumbing of [CursorDragGesture]: sees every touch of the window
 * before the views do (MainActivity's `dispatchTouchEvent`) and passes it on
 * unchanged, unless a first finger held still on the editor page for
 * [CursorDragGesture.HOLD_MS] while [enabled]. Then the page's touch is
 * cancelled, the phone gives a short buzz, and from there to the lift the
 * finger's moves are arrow keys through [sendKey], none of them reaching the
 * page. The activity itself delivers every touch this does not take.
 *
 * At the activity rather than on the WebView: the activity replaces its
 * WebView after a renderer crash, and a listener there would go with it.
 */
internal class CursorDragTouch(
    private val activity: Activity,
    private val webView: () -> WebView?,
    private val enabled: () -> Boolean,
    private val sendKey: (String) -> Unit,
) {
    private val gesture = run {
        val density = activity.resources.displayMetrics.density
        CursorDragGesture(
            slopPx = ViewConfiguration.get(activity).scaledTouchSlop.toFloat(),
            stepXPx = CursorDragGesture.STEP_X_DP * density,
            stepYPx = CursorDragGesture.STEP_Y_DP * density,
        )
    }
    private val handler = Handler(Looper.getMainLooper())
    private val hold = Runnable { startDrag() }

    /** The followed finger, by id: indices renumber as fingers come and go. */
    private var pointerId = MotionEvent.INVALID_POINTER_ID

    /** A copy of the touch's latest event, for the cancel the page gets when the hold fires. */
    private var latest: MotionEvent? = null

    /**
     * Follows [ev] and answers true when a cursor drag owns it: the activity
     * then does not deliver it. Answers false for everything else.
     */
    fun consume(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopWaiting()
                gesture.end()
                if (enabled() && overEditor(ev)) {
                    pointerId = ev.getPointerId(0)
                    gesture.down(ev.x, ev.y)
                    keep(ev)
                    handler.postDelayed(hold, CursorDragGesture.HOLD_MS)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gesture.otherPointerDown()
                if (gesture.state == CursorDragGesture.State.Idle) stopWaiting()
            }
            MotionEvent.ACTION_MOVE -> {
                val index = ev.findPointerIndex(pointerId)
                if (index >= 0 && gesture.state != CursorDragGesture.State.Idle) {
                    val keys = gesture.move(ev.getX(index), ev.getY(index))
                    when (gesture.state) {
                        CursorDragGesture.State.Idle -> stopWaiting()
                        CursorDragGesture.State.Waiting -> keep(ev)
                        CursorDragGesture.State.Dragging -> keys.forEach(sendKey)
                    }
                }
            }
            // The followed finger lifting while another stays: the drag holds
            // still until the last finger lifts, rather than handing the page
            // the rest of a touch it was told is cancelled.
            MotionEvent.ACTION_POINTER_UP ->
                if (ev.getPointerId(ev.actionIndex) == pointerId) pointerId = MotionEvent.INVALID_POINTER_ID
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopWaiting()
                pointerId = MotionEvent.INVALID_POINTER_ID
                if (gesture.end()) return true
            }
        }
        return gesture.state == CursorDragGesture.State.Dragging
    }

    private fun startDrag() {
        if (!gesture.holdElapsed()) return
        val last = latest ?: return
        // The page saw the touch begin; it is told the touch was cancelled, so
        // a tap, a scroll or its own long press selection does not complete
        // under the drag.
        val cancel = MotionEvent.obtain(last).apply { action = MotionEvent.ACTION_CANCEL }
        // To the view tree, as the activity delivers a touch.
        activity.window.superDispatchTouchEvent(cancel)
        cancel.recycle()
        forget()
        webView()?.let { view ->
            // Native focus only: arrow keys reach the page through the WebView.
            // The page's focus is already on the editor, which the Editor tab
            // gives it (diligent.mobile.showEditor).
            view.requestFocus()
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun stopWaiting() {
        handler.removeCallbacks(hold)
        forget()
    }

    private fun keep(ev: MotionEvent) {
        latest?.recycle()
        latest = MotionEvent.obtain(ev)
    }

    private fun forget() {
        latest?.recycle()
        latest = null
    }

    /** Whether the touch is on the editor page, in window coordinates (what the activity gets). */
    private fun overEditor(ev: MotionEvent): Boolean {
        val view = webView() ?: return false
        if (!view.isShown) return false
        val at = IntArray(2)
        view.getLocationInWindow(at)
        return ev.x >= at[0] && ev.x < at[0] + view.width && ev.y >= at[1] && ev.y < at[1] + view.height
    }
}
