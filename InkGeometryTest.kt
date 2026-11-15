package com.eliosnotes.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InkGeometryTest {
    private fun horizontal(id: String, y: Float = 100f) = Stroke(
        id, InkTool.PEN, 0xFF000000.toInt(), 2f,
        (0..20).map { InkPoint(it * 10f, y, 0.5f) } // x = 0..200
    )

    @Test
    fun partialEraseSplitsStrokeIntoTwoPieces() {
        val s = horizontal("a")
        val pieces = InkGeometry.erasePartial(s, cx = 100f, cy = 100f, r = 12f)
        assertEquals(2, pieces!!.size)
        // Both pieces stay clear of the erased circle and nothing from the original id survives.
        assertTrue(pieces[0].points.all { it.x < 100f - 12f })
        assertTrue(pieces[1].points.all { it.x > 100f + 12f })
        assertTrue(pieces.none { it.id == "a" })
    }

    @Test
    fun partialEraseAtTheEndShortensTheStroke() {
        val s = horizontal("a")
        val pieces = InkGeometry.erasePartial(s, cx = 0f, cy = 100f, r = 15f)
        assertEquals(1, pieces!!.size)
        assertTrue(pieces[0].points.first().x > 15f)
    }

    @Test
    fun untouchedStrokesKeepTheSameListInstance() {
        val strokes = listOf(horizontal("a"), horizontal("b", y = 400f))
        assertSame(strokes, InkGeometry.erasePartialAll(strokes, 100f, 700f, 10f))
        assertSame(strokes, InkGeometry.eraseWhole(strokes, 100f, 700f, 10f))
    }

    @Test
    fun eraseWholeRemovesOnlyTheTouchedStroke() {
        val strokes = listOf(horizontal("a"), horizontal("b", y = 400f))
        val out = InkGeometry.eraseWhole(strokes, 50f, 102f, 8f)
        assertEquals(listOf("b"), out.map { it.id })
    }

    @Test
    fun erasePartialAllKeepsOrderOfUntouchedStrokes() {
        val strokes = listOf(horizontal("a", 50f), horizontal("b"), horizontal("c", 300f))
        val out = InkGeometry.erasePartialAll(strokes, 100f, 100f, 12f)
        assertEquals("a", out.first().id)
        assertEquals("c", out.last().id)
        assertEquals(4, out.size) // a + two pieces of b + c
    }

    @Test
    fun singlePointDotIsRemovedWhenHit() {
        val dot = Stroke("d", InkTool.PEN, 0xFF000000.toInt(), 4f, listOf(InkPoint(10f, 10f)))
        assertEquals(emptyList<Stroke>(), InkGeometry.erasePartial(dot, 12f, 10f, 6f))
    }

    @Test
    fun sweepCoversTheWholePathWithBoundedSteps() {
        val pts = InkGeometry.sweep(0f, 0f, 100f, 0f, 10f)
        assertEquals(10, pts.size)
        assertEquals(100f, pts.last().x, 0.001f)
        var prev = 0f
        for (p in pts) { assertTrue(p.x - prev <= 10.001f); prev = p.x }
    }

    @Test
    fun sweepOfZeroLengthStillReturnsTheEndPoint() {
        assertEquals(1, InkGeometry.sweep(5f, 5f, 5f, 5f, 10f).size)
    }

    @Test
    fun longSweepDoesNotCapSamplesAndKeepsTheMaximumStep() {
        val pts = InkGeometry.sweep(0f, 0f, 5000f, 0f, 10f)
        assertEquals(500, pts.size)
        var prev = 0f
        for (p in pts) {
            assertTrue(p.x - prev <= 10.001f)
            prev = p.x
        }
    }

    @Test
    fun fastEraserStrokeCannotSkipOverInk() {
        // A short vertical stroke at x=100; the eraser jumps from x=0 to x=200 in one event.
        val stroke = Stroke("v", InkTool.PEN, 0xFF000000.toInt(), 2f, (0..4).map { InkPoint(100f, 90f + it * 5f) })
        var strokes = listOf(stroke)
        for (p in InkGeometry.sweep(0f, 100f, 200f, 100f, 6f)) {
            strokes = InkGeometry.erasePartialAll(strokes, p.x, p.y, 10f)
        }
        assertTrue("the stroke must be erased", strokes.isEmpty())
    }
}
