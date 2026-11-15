package com.eliosnotes.app.data.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextCodecTest {
    @Test
    fun roundTripKeepsArabicEnglishAndNewlines() {
        val text = "مرحبا بالعالم\nHello \u2014 سطر ثانٍ \"quoted\""
        assertEquals(text, TextCodec.decode(TextCodec.encode(text)))
    }

    @Test
    fun emptyTextIsValidNotCorrupt() {
        assertEquals("", TextCodec.decodeOrNull(TextCodec.encode("")))
    }

    @Test
    fun corruptPayloadIsReportedAsNullSoItCanBeProtected() {
        assertNull(TextCodec.decodeOrNull("not json"))
        assertNull(TextCodec.decodeOrNull("{}"))
        assertEquals("", TextCodec.decode("not json"))
    }
}
