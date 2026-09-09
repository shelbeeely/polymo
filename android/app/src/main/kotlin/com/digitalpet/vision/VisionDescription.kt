package com.digitalpet.vision

/**
 * Turns a list of [VisionFinding]s into the one paragraph of grounding text
 * that gets folded into a persona turn — see
 * [com.digitalpet.conversation.PetConversationEngine.describeSight].
 *
 * Pure Kotlin, no ML Kit and no Android — a plain function over plain data,
 * which is what makes it unit-testable at all.
 */
object VisionDescription {

    /**
     * @return the grounding paragraph, or `""` if nothing was detected —
     *   never a made-up "I don't see anything", since that is a claim about
     *   the frame this function has no evidence for either way. An empty
     *   string is a fact the caller can act on; a caller that wants a
     *   fallback line supplies its own.
     */
    fun build(findings: List<VisionFinding>): String {
        val faceCount = findings.filterIsInstance<VisionFinding.FacesPresent>()
            .firstOrNull()?.count ?: 0
        val meshTracked = findings.filterIsInstance<VisionFinding.FaceMeshTracked>()
            .firstOrNull()?.let { it.faceCount > 0 } ?: false

        val sentences = buildList {
            findings.forEach { finding ->
                // FaceMeshTracked never speaks on its own — it is an
                // enrichment clause on FacesPresent's sentence, not a second
                // sentence saying "a face is visible" twice over. Merged
                // here rather than skipped: a face mesh with no matching
                // FacesPresent entry (a caller that only ran the mesh
                // detector) should still say something.
                if (finding is VisionFinding.FaceMeshTracked) {
                    if (!meshTracked || faceCount <= 0) finding.describe()?.let { add(it) }
                    return@forEach
                }
                val clause = finding.describe() ?: return@forEach
                if (finding is VisionFinding.FacesPresent && meshTracked) {
                    add(clause.removeSuffix(".") + ", with detailed facial geometry tracked.")
                } else {
                    add(clause)
                }
            }
        }

        return sentences.joinToString(" ")
    }
}
