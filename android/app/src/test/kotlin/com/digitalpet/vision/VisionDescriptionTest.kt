package com.digitalpet.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here was checked by breaking [VisionDescription]/[VisionFinding]
 * and confirming the test fails — CLAUDE.md's rule for tests that read files
 * or, here, that a compiler cannot check the CONTENT of a string against.
 */
class VisionDescriptionTest {

    @Test
    fun `no findings produce no text`() {
        assertEquals("", VisionDescription.build(emptyList()))
    }

    @Test
    fun `labels with nothing detected say nothing`() {
        assertEquals("", VisionDescription.build(listOf(VisionFinding.Labels(emptyList()))))
    }

    @Test
    fun `labels list names what was seen`() {
        val text = VisionDescription.build(listOf(VisionFinding.Labels(listOf("cup", "table"))))
        assertEquals("In the frame: cup, table.", text)
    }

    @Test
    fun `a single face is singular`() {
        val text = VisionDescription.build(listOf(VisionFinding.FacesPresent(1)))
        assertEquals("A face is visible.", text)
    }

    @Test
    fun `multiple faces are plural and counted`() {
        val text = VisionDescription.build(listOf(VisionFinding.FacesPresent(3)))
        assertEquals("3 faces are visible.", text)
    }

    @Test
    fun `zero faces say nothing`() {
        assertEquals("", VisionDescription.build(listOf(VisionFinding.FacesPresent(0))))
    }

    @Test
    fun `face mesh alongside face presence merges into one sentence, not two`() {
        val text = VisionDescription.build(
            listOf(VisionFinding.FacesPresent(1), VisionFinding.FaceMeshTracked(1, 468))
        )
        assertEquals("A face is visible, with detailed facial geometry tracked.", text)
        // The regression this guards: two detectors both reporting "a face"
        // must never read as two separate faces or two separate sentences.
        assertFalse(text.contains(". Detailed facial geometry"))
    }

    @Test
    fun `face mesh with no matching face-presence finding still speaks`() {
        val text = VisionDescription.build(listOf(VisionFinding.FaceMeshTracked(1, 468)))
        assertEquals("Detailed facial geometry is being tracked.", text)
    }

    @Test
    fun `face mesh with zero faces says nothing, merged or not`() {
        assertEquals(
            "",
            VisionDescription.build(
                listOf(VisionFinding.FacesPresent(0), VisionFinding.FaceMeshTracked(0, 0))
            )
        )
    }

    @Test
    fun `a single tracked pose is singular`() {
        assertEquals(
            "A person's pose is being tracked.",
            VisionDescription.build(listOf(VisionFinding.PosesTracked(1))),
        )
    }

    @Test
    fun `barcode values are read out literally`() {
        assertEquals(
            "Barcode reads: 012345.",
            VisionDescription.build(listOf(VisionFinding.Barcodes(listOf("012345")))),
        )
    }

    @Test
    fun `recognized text is quoted`() {
        assertEquals(
            "Text visible: \"Hello world\".",
            VisionDescription.build(listOf(VisionFinding.RecognizedText("Hello world"))),
        )
    }

    @Test
    fun `blank recognized text says nothing`() {
        assertEquals("", VisionDescription.build(listOf(VisionFinding.RecognizedText("   "))))
    }

    @Test
    fun `subject separation below the meaningful threshold says nothing`() {
        assertEquals(
            "",
            VisionDescription.build(listOf(VisionFinding.SubjectSeparated(0.05f))),
        )
    }

    @Test
    fun `subject separation above threshold reports a percentage`() {
        val text = VisionDescription.build(listOf(VisionFinding.SubjectSeparated(0.5f)))
        assertTrue(text.contains("50%"))
    }

    @Test
    fun `findings combine in roster order, space separated`() {
        val text = VisionDescription.build(
            listOf(
                VisionFinding.Labels(listOf("mug")),
                VisionFinding.FacesPresent(1),
            )
        )
        assertEquals("In the frame: mug. A face is visible.", text)
    }

    @Test
    fun `objects list names tracked labels`() {
        val text = VisionDescription.build(listOf(VisionFinding.Objects(listOf("chair", "lamp"))))
        assertEquals("Tracked objects: chair, lamp.", text)
    }
}
