package com.digitalpet.vision

/**
 * One on-device vision detector's result, already reduced to plain data —
 * no `Bitmap`, no ML Kit types. [VisionAnalyzer] is the only thing that talks
 * to ML Kit; everything downstream of it, including [describe] below, is
 * pure Kotlin and unit-testable without a device.
 *
 * **Every finding here is grounding CONTEXT, not an independent reply.** Per
 * the plan's governing principle — the Pixel 10 is the pet's second brain —
 * these fold into one turn of [com.digitalpet.conversation.PetConversationEngine],
 * the same reply pipeline a spoken question runs through. A bounding box or a
 * raw pose skeleton is not speakable; [describe] is what turns each one into
 * a sentence a persona prompt can build on.
 *
 * **Face Detection and Face Mesh Detection are both presence-only, on
 * purpose.** Neither carries an identity — nothing here says WHO is in
 * frame, only that a face is, and (for the mesh) how many landmark points
 * were tracked. People *identification* was explicitly excluded after asking;
 * Face Mesh Detection's more granular geometry was explicitly kept in after
 * confirming it still isn't identity matching. Digital Ink Recognition is
 * not in this file at all — it takes stylus/touch input, not a camera frame,
 * so it was never in scope here.
 */
sealed interface VisionFinding {

    /** The clause this finding contributes to the grounding text, or `null` if it has nothing to say. */
    fun describe(): String?

    /** Image Labeling — objects/animals/activities recognised in the frame. */
    data class Labels(val labels: List<String>) : VisionFinding {
        override fun describe(): String? {
            if (labels.isEmpty()) return null
            return "In the frame: ${labels.joinToString(", ")}."
        }
    }

    /** Face Detection — presence and count only, never identity. */
    data class FacesPresent(val count: Int) : VisionFinding {
        override fun describe(): String? = when {
            count <= 0 -> null
            count == 1 -> "A face is visible."
            else -> "$count faces are visible."
        }
    }

    /**
     * Face Mesh Detection — still presence-only, but a second, independent
     * detector, so it is reported as an enrichment rather than a duplicate
     * sentence when [FacesPresent] already said a face is there. See
     * [VisionDescription.build], which is what actually merges the two.
     */
    data class FaceMeshTracked(val faceCount: Int, val pointsPerFace: Int) : VisionFinding {
        override fun describe(): String? {
            if (faceCount <= 0) return null
            return "Detailed facial geometry is being tracked."
        }
    }

    /** Pose Detection — human body position. */
    data class PosesTracked(val count: Int) : VisionFinding {
        override fun describe(): String? = when {
            count <= 0 -> null
            count == 1 -> "A person's pose is being tracked."
            else -> "$count people's poses are being tracked."
        }
    }

    /** Object Detection and Tracking. */
    data class Objects(val labels: List<String>) : VisionFinding {
        override fun describe(): String? {
            if (labels.isEmpty()) return null
            return "Tracked objects: ${labels.joinToString(", ")}."
        }
    }

    /** Barcode Scanning — the literal decoded value(s), not an image description. */
    data class Barcodes(val values: List<String>) : VisionFinding {
        override fun describe(): String? {
            if (values.isEmpty()) return null
            return "Barcode reads: ${values.joinToString(", ")}."
        }
    }

    /** Text Recognition (OCR). */
    data class RecognizedText(val text: String) : VisionFinding {
        override fun describe(): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return "Text visible: \"$trimmed\"."
        }
    }

    /**
     * Selfie/Subject Segmentation. `coverage` is the fraction of the frame
     * the model assigns to a foreground subject, 0f-1f. Included for
     * completeness of the roster — no biometric concern, since it draws a
     * mask, not an identity.
     */
    data class SubjectSeparated(val coverage: Float) : VisionFinding {
        private companion object {
            /** Below this, "foreground" is mostly noise, not a subject worth mentioning. */
            const val MEANINGFUL_COVERAGE = 0.12f
        }

        override fun describe(): String? {
            if (coverage < MEANINGFUL_COVERAGE) return null
            return "A distinct subject fills about ${(coverage * 100).toInt()}% of the frame, " +
                "separated from the background."
        }
    }
}
