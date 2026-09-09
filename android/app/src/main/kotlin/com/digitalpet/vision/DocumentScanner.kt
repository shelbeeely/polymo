package com.digitalpet.vision

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.tasks.await

/**
 * The personal document-scanning utility from the ML Kit Vision roster —
 * "help me personally with scanning a document," per the user's own request.
 *
 * **This is a UI flow the user triggers directly, not a frame detector.**
 * Everything else in `vision/` feeds [VisionAnalyzer]'s findings into the
 * pet's perception; this class's output — the scanned pages themselves — is
 * for the user, never for the pet. See
 * [com.digitalpet.conversation.PetConversationEngine.acknowledgeDocumentScan],
 * which only ever receives a page count, never the scan itself.
 *
 * Thin on purpose: `GmsDocumentScanning`'s own client already does the real
 * work (its own capture UI, edge detection, cropping), so this exists only to
 * give the rest of the app one small, testable-by-inspection surface instead
 * of three scattered Play-services calls.
 */
object DocumentScanner {

    private val options: GmsDocumentScannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(false)
        .setResultFormats(
            GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
            GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
        )
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    /** The `IntentSender` to launch via `ActivityResultContracts.StartIntentSenderForResult`. */
    suspend fun startScanIntentSender(activity: Activity): IntentSender =
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity).await()

    /** How many pages were captured, or null if the result did not carry a scan. */
    fun pageCountFrom(resultIntent: Intent?): Int? =
        GmsDocumentScanningResult.fromActivityResultIntent(resultIntent)?.pages?.size
}
