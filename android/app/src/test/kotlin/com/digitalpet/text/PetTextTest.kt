package com.digitalpet.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text bound for the pet's screen or for TTS.
 *
 * Every function here has already caused a real bug: markup reaching TTS as
 * garbled noise mid-sentence, and a byte-wise truncation that split a
 * multi-byte character and made the firmware reject the whole write.
 */
class PetTextTest {

    // ---- stripMarkup -------------------------------------------------------

    @Test
    fun `emphasis and code markers are removed`() {
        // These are what Piper was pronouncing aloud.
        assertEquals("Slack has 3 messages", PetText.stripMarkup("**Slack** has *3* messages"))
        assertEquals("run it", PetText.stripMarkup("`run it`"))
        assertEquals("very important", PetText.stripMarkup("~~very~~ _important_"))
    }

    @Test
    fun `headings and bullets lose their markers but keep their words`() {
        assertEquals("Today", PetText.stripMarkup("## Today"))
        assertEquals("milk\neggs", PetText.stripMarkup("- milk\n- eggs"))
        assertEquals("one\ntwo", PetText.stripMarkup("• one\n· two"))
    }

    @Test
    fun `links keep the label and lose the url`() {
        // The pet cannot follow a link, and Piper would read the URL out.
        assertEquals("see the docs", PetText.stripMarkup("see [the docs](https://example.com)"))
    }

    @Test
    fun `ordinary prose is left alone`() {
        // The guard against an over-eager regex: this runs on every reply, and
        // silently mangling normal text would be worse than leaving markup in.
        val prose = "It's 3 p.m. — did you eat? I hope so; you seemed tired."
        assertEquals(prose, PetText.stripMarkup(prose))
    }

    @Test
    fun `a hyphenated word is not treated as a bullet`() {
        // The bullet rule is anchored to line starts precisely so this survives.
        assertEquals("a well-earned break", PetText.stripMarkup("a well-earned break"))
    }

    // ---- truncateUtf8 ------------------------------------------------------

    @Test
    fun `text within the limit is unchanged`() {
        val bytes = PetText.truncateUtf8("hello", 240)
        assertEquals("hello", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `truncation never splits a multi-byte character`() {
        // The original bug. "é" is two bytes; cutting at 3 bytes must drop the
        // whole character rather than leave half of it, which is invalid UTF-8
        // and made the firmware reject the write with ATT_ERR_UNLIKELY.
        val bytes = PetText.truncateUtf8("aéé", 3)
        val decoded = String(bytes, Charsets.UTF_8)

        assertEquals("aé", decoded)
        // Round-tripping proves it is still valid UTF-8, which is the real
        // property — a replacement character would mean we produced garbage.
        assertTrue("produced invalid UTF-8", !decoded.contains('�'))
    }

    @Test
    fun `truncation handles characters wider than two bytes`() {
        // Emoji are four bytes and the model emits them constantly.
        val bytes = PetText.truncateUtf8("hi 😊", 5)
        assertEquals("hi ", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `truncation never exceeds the limit`() {
        val long = "é".repeat(200)
        for (limit in 0..40) {
            assertTrue(
                "limit $limit produced ${PetText.truncateUtf8(long, limit).size} bytes",
                PetText.truncateUtf8(long, limit).size <= limit
            )
        }
    }

    // ---- fitForDisplay -----------------------------------------------------

    @Test
    fun `short text passes through untouched`() {
        val text = "all good"
        assertEquals(text, String(PetText.fitForDisplay(text, 240), Charsets.UTF_8))
    }

    @Test
    fun `overlong text is cut back to a sentence boundary`() {
        val text = "The first sentence is here. The second one runs on much longer than the pet can show."
        val out = String(PetText.fitForDisplay(text, 40), Charsets.UTF_8)

        // Ending mid-word reads as a glitch; ending at a full stop reads as
        // brevity.
        assertEquals("The first sentence is here.…", out)
    }

    @Test
    fun `the result always fits the byte budget`() {
        // This is the property the firmware actually enforces: a write over the
        // limit is rejected outright, so an ellipsis appended *past* the budget
        // would lose the whole message rather than its tail.
        val text = "Some prose with é and 😊 that keeps going on and on and on for quite a while."
        for (limit in 8..80) {
            val size = PetText.fitForDisplay(text, limit).size
            assertTrue("limit $limit produced $size bytes", size <= limit)
        }
    }

    @Test
    fun `a short leading fragment is not cut back to it`() {
        // With a terminator before MIN_SENTENCE_CHARS, keeping only "Hi." would
        // tell the reader less than a clipped sentence does.
        val text = "Hi. Then a much longer second sentence that will certainly not fit."
        val out = String(PetText.fitForDisplay(text, 40), Charsets.UTF_8)

        assertTrue("expected a clipped sentence, got \"$out\"", out.length > 10)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `truncated output stays valid UTF-8`() {
        val text = "Café ☕ time — " + "é".repeat(100)
        for (limit in 8..60) {
            val decoded = String(PetText.fitForDisplay(text, limit), Charsets.UTF_8)
            assertTrue("limit $limit produced garbage", !decoded.contains('�'))
        }
    }

}
