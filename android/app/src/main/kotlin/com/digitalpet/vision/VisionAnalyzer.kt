package com.digitalpet.vision

import android.graphics.Bitmap
import com.digitalpet.util.DiagnosticLogger
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the full ML Kit Vision roster over one still and reduces every
 * detector's output to plain [VisionFinding]s.
 *
 * **Every detector runs, and every detector's failure is isolated.** This is
 * "every API if possible," not a curated subset, and a persona reply
 * grounded in seven working detectors is better than no reply because an
 * eighth one threw — a corrupt frame, a model still downloading, whatever it
 * is. Each detector is wrapped in its own `runCatching` and a failure is
 * logged and simply produces no finding, the same shape [LlmManager] uses
 * for `ModelState.Error`.
 *
 * **All single-image mode.** Confirmed against ML Kit's own docs: nearly
 * every detector here processes one frame exactly as well in single-image
 * mode as in streaming mode — the underlying model runs on one frame either
 * way. Continuous/live tracking is what phase 10's docked USB feed would
 * add; a still from this phone's own camera has no live feed to track
 * across, so single-image mode is not a compromise here, it is the correct
 * mode for the input this class is actually given.
 *
 * **Document Scanner is not here.** It is a UI flow the user triggers
 * directly (see `VisionScreen.kt`), not a frame detector feeding this
 * pipeline — its output is for the user, not the pet's perception, per the
 * explicit privacy line in the plan this class implements.
 */
@Singleton
class VisionAnalyzer @Inject constructor(
    private val diagnosticLogger: DiagnosticLogger,
) {
    private companion object {
        const val TAG = "VisionAnalyzer"
    }

    // Lazy, same pattern as LlmManager/SttService: nothing is built until the
    // first real capture, and every client then lives for the process — nothing
    // here is torn down on a UI teardown, since there is no UI-scoped owner.
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }

    // FAST rather than ACCURATE: this runs on a single interactive capture, not
    // a background batch, and presence is all this app ever reports either way.
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
    }

    private val faceMeshDetector by lazy {
        FaceMeshDetection.getClient(
            FaceMeshDetectorOptions.Builder()
                .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
                .build()
        )
    }

    private val poseDetector by lazy {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    private val objectDetector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        )
    }

    private val barcodeScanner by lazy { BarcodeScanning.getClient() }

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    /**
     * @param rotationDegrees how far the bitmap must be rotated clockwise to
     *   appear upright — CameraX hands this back with every capture, and every
     *   detector here needs it to read faces/text/objects the right way up.
     * @return every finding that succeeded and had something to report, in
     *   roster order. A detector that failed or found nothing is simply
     *   absent — see the class doc for why that is the point, not a gap.
     */
    suspend fun analyze(bitmap: Bitmap, rotationDegrees: Int = 0): List<VisionFinding> =
        coroutineScope {
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)

            val labels = async { runDetector("labeling") { labelsOf(image) } }
            val faces = async { runDetector("face") { facesOf(image) } }
            val faceMesh = async { runDetector("face-mesh") { faceMeshOf(image) } }
            val poses = async { runDetector("pose") { posesOf(image) } }
            val objects = async { runDetector("object") { objectsOf(image) } }
            val barcodes = async { runDetector("barcode") { barcodesOf(image) } }
            val text = async { runDetector("text") { textOf(image) } }
            val subject = async { runDetector("segmentation") { subjectOf(image) } }

            listOfNotNull(
                labels.await(),
                faces.await(),
                faceMesh.await(),
                poses.await(),
                objects.await(),
                barcodes.await(),
                text.await(),
                subject.await(),
            )
        }

    private suspend fun runDetector(name: String, block: suspend () -> VisionFinding?): VisionFinding? =
        try {
            block()
        } catch (e: Exception) {
            diagnosticLogger.log(TAG, "$name detector failed: ${e.message}")
            null
        }

    private suspend fun labelsOf(image: InputImage): VisionFinding.Labels {
        val labels = labeler.process(image).await()
        return VisionFinding.Labels(labels.mapNotNull { it.text.takeIf { t -> t.isNotBlank() } })
    }

    private suspend fun facesOf(image: InputImage): VisionFinding.FacesPresent {
        val faces = faceDetector.process(image).await()
        return VisionFinding.FacesPresent(faces.size)
    }

    private suspend fun faceMeshOf(image: InputImage): VisionFinding.FaceMeshTracked {
        val meshes = faceMeshDetector.process(image).await()
        val pointsPerFace = meshes.firstOrNull()?.allPoints?.size ?: 0
        return VisionFinding.FaceMeshTracked(meshes.size, pointsPerFace)
    }

    private suspend fun posesOf(image: InputImage): VisionFinding.PosesTracked {
        // The base ML Kit Pose Detection model tracks one person per frame —
        // there is no multi-pose model in this SDK, unlike the detectors above.
        val pose = poseDetector.process(image).await()
        val tracked = if (pose.allPoseLandmarks.isNotEmpty()) 1 else 0
        return VisionFinding.PosesTracked(tracked)
    }

    private suspend fun objectsOf(image: InputImage): VisionFinding.Objects {
        val objects = objectDetector.process(image).await()
        val labels = objects.mapNotNull { it.labels.firstOrNull()?.text }
        return VisionFinding.Objects(labels)
    }

    private suspend fun barcodesOf(image: InputImage): VisionFinding.Barcodes {
        val barcodes = barcodeScanner.process(image).await()
        return VisionFinding.Barcodes(barcodes.mapNotNull { it.rawValue })
    }

    private suspend fun textOf(image: InputImage): VisionFinding.RecognizedText {
        val result = textRecognizer.process(image).await()
        return VisionFinding.RecognizedText(result.text)
    }

    private suspend fun subjectOf(image: InputImage): VisionFinding.SubjectSeparated {
        val mask = segmenter.process(image).await()
        val buffer = mask.buffer
        buffer.rewind()
        var sum = 0f
        var count = 0
        while (buffer.hasRemaining()) {
            sum += buffer.float
            count++
        }
        val coverage = if (count > 0) sum / count else 0f
        return VisionFinding.SubjectSeparated(coverage)
    }
}
