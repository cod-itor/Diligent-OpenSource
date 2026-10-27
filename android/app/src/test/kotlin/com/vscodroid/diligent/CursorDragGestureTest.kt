package com.vscodroid.diligent

import com.vscodroid.diligent.CursorDragGesture.Companion.ARROW_DOWN
import com.vscodroid.diligent.CursorDragGesture.Companion.ARROW_LEFT
import com.vscodroid.diligent.CursorDragGesture.Companion.ARROW_RIGHT
import com.vscodroid.diligent.CursorDragGesture.Companion.ARROW_UP
import com.vscodroid.diligent.CursorDragGesture.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CursorDragGestureTest {

    // Slop 8, ten pixels a character, twenty a line.
    private fun gesture() = CursorDragGesture(slopPx = 8f, stepXPx = 10f, stepYPx = 20f)

    private fun dragging(): CursorDragGesture = gesture().apply {
        down(100f, 100f)
        assertTrue(holdElapsed())
    }

    @Test
    fun `a tap never becomes a drag`() {
        val g = gesture()
        g.down(100f, 100f)
        g.move(102f, 101f)
        assertFalse(g.end(), "the page must see the lift of a tap")
        assertFalse(g.holdElapsed(), "a hold timer firing after the lift started a drag")
        assertEquals(State.Idle, g.state)
    }

    @Test
    fun `a swipe before the hold is the page's scroll, for the whole touch`() {
        val g = gesture()
        g.down(100f, 100f)
        assertEquals(emptyList<String>(), g.move(100f, 140f))
        assertEquals(State.Idle, g.state)
        assertFalse(g.holdElapsed())
        assertEquals(emptyList<String>(), g.move(100f, 300f))
    }

    @Test
    fun `a second finger before the hold is a pinch`() {
        val g = gesture()
        g.down(100f, 100f)
        g.otherPointerDown()
        assertFalse(g.holdElapsed())
    }

    @Test
    fun `a little jitter while holding still is allowed`() {
        val g = gesture()
        g.down(100f, 100f)
        g.move(105f, 104f)
        assertTrue(g.holdElapsed())
        assertEquals(State.Dragging, g.state)
    }

    @Test
    fun `sideways is characters, up and down is lines`() {
        val g = dragging()
        assertEquals(listOf(ARROW_RIGHT, ARROW_RIGHT, ARROW_RIGHT), g.move(130f, 100f))
        assertEquals(listOf(ARROW_LEFT), g.move(120f, 100f))
        assertEquals(listOf(ARROW_DOWN, ARROW_DOWN), g.move(120f, 140f))
        assertEquals(listOf(ARROW_UP), g.move(120f, 120f))
    }

    @Test
    fun `small moves add up and a diagonal gives both axes`() {
        val g = dragging()
        assertEquals(emptyList<String>(), g.move(104f, 100f))
        assertEquals(emptyList<String>(), g.move(108f, 100f))
        assertEquals(listOf(ARROW_RIGHT), g.move(112f, 100f))
        assertEquals(listOf(ARROW_RIGHT, ARROW_DOWN), g.move(122f, 120f))
    }

    @Test
    fun `the drag measures from where the finger was when the hold fired`() {
        val g = gesture()
        g.down(100f, 100f)
        g.move(106f, 100f)
        assertTrue(g.holdElapsed())
        // 9 px from the hold point: not yet a character, though 15 from the first touch.
        assertEquals(emptyList<String>(), g.move(115f, 100f))
    }

    @Test
    fun `a second finger during a drag does not stop it`() {
        val g = dragging()
        g.otherPointerDown()
        assertEquals(listOf(ARROW_RIGHT), g.move(110f, 100f))
    }

    @Test
    fun `lifting ends the drag and the next touch starts fresh`() {
        val g = dragging()
        g.move(105f, 100f)
        assertTrue(g.end(), "the lift of a drag must not reach the page")
        assertEquals(State.Idle, g.state)
        assertEquals(emptyList<String>(), g.move(200f, 100f))
        g.down(0f, 0f)
        assertTrue(g.holdElapsed())
        // The 5 px left over from the last drag are gone.
        assertEquals(emptyList<String>(), g.move(6f, 0f))
    }

    @Test
    fun `the hold is shorter than Android's long press`() {
        // ViewConfiguration's long press timeout is 400 ms or more; the hold must
        // win, or the page starts its own long press selection first.
        assertTrue(CursorDragGesture.HOLD_MS < 400)
    }
}
