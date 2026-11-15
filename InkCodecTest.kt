package com.eliosnotes.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InkCodecTest {
    private fun sample() = Stroke(
        id = "s1", tool = InkTool.PEN, colorArgb = 0xFF1E4FA3.toInt(), width = 3.5f,
        points = listOf(
            InkPoint(10.0f, 20.5f, 0.2f),
            InkPoint(12.3f, 25.1f, 0.55f),
            InkPoint(11.9f, 40.0f, 0.9f),
            InkPoint(300.4f, 1100.2f, 0.0f)
        )
    )

    @Test
    fun roundTripKeepsEverythingWithinQuantisation() {
        val original = sample()
        val decoded = InkCodec.decode("s1", InkCodec.encode(original))
        assertNotNull(decoded)
        decoded!!
        assertEquals(original.tool, decoded.tool)
        assertEquals(original.colorArgb, decoded.colorArgb)
        assertEquals(original.width, decoded.width, 0.0001f)
        assertEquals(original.points.size, decoded.points.size)
        original.points.zip(decoded.points).forEach { (a, b) ->
            assertEquals(a.x, b.x, 0.051f)
            assertEquals(a.y, b.y, 0.051f)
            assertEquals(a.pressure, b.pressure, 0.001f)
        }
    }

    @Test
    fun emptyStrokeRoundTrips() {
        val empty = Stroke("e", InkTool.HIGHLIGHTER, 0x00FFEB3B, 18f, emptyList())
        val decoded = InkCodec.decode("e", InkCodec.encode(empty))
        assertNotNull(decoded)
        assertEquals(0, decoded!!.points.size)
        assertEquals(InkTool.HIGHLIGHTER, decoded.tool)
    }

    @Test
    fun corruptPayloadsReturnNullInsteadOfThrowing() {
        assertNull(InkCodec.decode("x", "not json"))
        assertNull(InkCodec.decode("x", """{"tool":"PEN","color":1,"width":2.0,"pts":"1 2"}"""))
        assertNull(InkCodec.decode("x", """{"tool":"PEN","color":1,"width":2.0,"pts":"a b c"}"""))
    }

    @Test
    fun unknownToolFallsBackToPen() {
        val s = InkCodec.decode("x", """{"tool":"LASER","color":-1,"width":2.0,"pts":"10 10 500"}""")
        assertNotNull(s)
        assertEquals(InkTool.PEN, s!!.tool)
    }
}
