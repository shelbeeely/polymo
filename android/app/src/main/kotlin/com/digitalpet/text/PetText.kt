package com.digitalpet.text

/**
 * Preparing model output for consumers that cannot take it raw.
 *
 * The LLM writes for a chat window: markdown, emoji, arbitrary length. Two
 * consumers cannot cope with that. The pet's screen renders LVGL's Montserrat
 * — no markup, no emoji — and holds 240 bytes. The TTS engine pronounces
 * punctuation it does not understand, so "**Slack**" comes out as noise
 * around the word.
 *
 * The chat itself keeps the original text; only these two get the cleaned
 * version.
 *
 * Everything here is pure, and every function has already produced a real bug:
 * markup reaching TTS as garbled noise, and a byte-truncation that split a
 * multi-byte character in half.
 */
object PetText {

    /**
     * Below this many characters, a truncated message is not cut back to its
     * last sentence — a two-word fragment tells the reader less than a clipped
     * sentence does.
     */
    const val MIN_SENTENCE_CHARS = 20

    /**
     * Remove markdown and decorative characters.
     *
     * The model still emits markup despite being told not to. Piper reads those
     * characters literally — asterisks came out as garbled noise mid-sentence —
     * and the pet's display cannot render them either.
     */
    fun stripMarkup(text: String): String =
        text
            .replace(Regex("""[*_`~]+"""), "")                              // emphasis, code
            .replace(Regex("""^\s*#{1,6}\s*""", RegexOption.MULTILINE), "") // headings
            .replace(Regex("""^\s*[-•·]\s+""", RegexOption.MULTILINE), "")  // bullets
            .replace(Regex("""\[([^]]*)]\([^)]*\)"""), "$1")                // links -> label
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()

    /**
     * Truncate to at most [maxBytes] of UTF-8 **without splitting a character**.
     *
     * The naive version of this — copying [maxBytes] raw bytes — produced
     * invalid UTF-8 whenever the cut landed mid-character, which the model makes
     * likely because its replies are full of non-ASCII punctuation.
     */
    fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        // Continuation bytes are 10xxxxxx; walk back off any partial character.
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }

    /**
     * Fit [text] into [maxBytes] for the pet's screen, cutting at a sentence
     * boundary where there is one.
     *
     * Stopping mid-sentence reads as a glitch; stopping at a full stop reads as
     * brevity. The ellipsis is what tells the reader something was dropped, so
     * its own bytes have to come out of the budget rather than be appended past
     * it — that would push the write over the firmware's limit and be rejected.
     */
    fun fitForDisplay(text: String, maxBytes: Int): ByteArray {
        val full = text.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full

        val ellipsis = "…"
        val budget = maxBytes - ellipsis.toByteArray(Charsets.UTF_8).size

        // Last sentence terminator that still fits inside the budget.
        val head = String(truncateUtf8(text, budget), Charsets.UTF_8)
        val lastEnd = head.indexOfLast { it == '.' || it == '!' || it == '?' }
        val kept = if (lastEnd >= MIN_SENTENCE_CHARS) head.substring(0, lastEnd + 1)
                   else head.trimEnd()

        return (kept + ellipsis).toByteArray(Charsets.UTF_8)
    }
}
