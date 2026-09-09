package com.digitalpet.pet

import com.digitalpet.util.DiagnosticLogger
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether THIS device can run PolyMO at all.
 *
 * **This is the hard gate, not a soft "some features degraded" check.** PolyMO
 * only exists on AICore now — the LLM (Prompt API) and speech recognition
 * (Speech Recognition Advanced mode) are both Gemini Nano, both go through
 * this same system service, and Advanced mode is Pixel 10/11 only as of this
 * writing. A device that fails this never gets to the conversation flow; see
 * [com.digitalpet.pet.PetReadiness.DeviceUnsupported] and
 * `FirstRunStep.DEVICE_UNSUPPORTED`, which is the one first-run step this
 * product does not let you step over.
 *
 * **Two independent `checkStatus()` calls, folded into one answer**, because
 * either one being unavailable makes the pet unusable — there is no version
 * of this app that thinks but cannot hear, or hears but cannot think.
 * Advanced speech recognition is narrower than the Prompt API today (fewer
 * devices), so in practice it is the one that actually gates eligibility, but
 * both are checked rather than assumed.
 */
sealed interface AiCoreStatus {
    /** Neither check has resolved yet. The launch screen, effectively. */
    data object Checking : AiCoreStatus

    /**
     * AICore supports this device but Gemini Nano is not downloaded yet.
     * [download] is the Flow to collect to drive it; re-running [AiCoreAvailability.refresh]
     * afterwards is what moves this to [Available].
     */
    data class Downloadable(val download: suspend () -> Flow<DownloadStatus>) : AiCoreStatus

    /** Currently downloading. Distinct from [Downloadable] so the UI can show progress. */
    data object Downloading : AiCoreStatus

    /** Both the Prompt API and Speech Recognition Advanced mode are ready. */
    data object Available : AiCoreStatus

    /**
     * Either feature reported [FeatureStatus.UNAVAILABLE] — the device does not
     * have AICore, or does not have it configured for Gemini Nano, or (Speech
     * Recognition specifically) has an unlocked bootloader. Terminal: nothing
     * in this app can fix it, unlike a missing model file used to be.
     */
    data object Unsupported : AiCoreStatus
}

/**
 * Owns the one [StateFlow] both [PetReadiness] and first run read to decide
 * whether this device is allowed into the app at all.
 *
 * A singleton, like [com.digitalpet.data.ModelRepository] used to be the
 * single source of truth for the three old model slots — this replaces that
 * role with a single device-eligibility question instead of three files.
 */
@Singleton
class AiCoreAvailability @Inject constructor(
    private val logger: DiagnosticLogger,
) {
    private companion object {
        const val TAG = "AiCoreAvailability"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<AiCoreStatus>(AiCoreStatus.Checking)
    val status: StateFlow<AiCoreStatus> = _status.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    /**
     * Re-runs both eligibility checks. Called once at startup and again after
     * a [AiCoreStatus.Downloadable] download completes, since a device that
     * was downloadable a moment ago should now read [AiCoreStatus.Available].
     */
    suspend fun refresh() {
        _status.value = AiCoreStatus.Checking
        try {
            val promptStatus = Generation.getClient().checkStatus()
            val speechStatus = SpeechRecognition.getClient(advancedSpeechOptions()).checkStatus()

            _status.value = fold(promptStatus, speechStatus)
        } catch (e: Exception) {
            // A device this exotic (AICore present but the client throws) is
            // treated the same as UNAVAILABLE — there is no third bucket for
            // "probably broken", and Unsupported is the safe direction to be
            // wrong in given this app refuses to run without it either way.
            logger.log(TAG, "AICore eligibility check threw: ${e.message}")
            _status.value = AiCoreStatus.Unsupported
        }
    }

    /**
     * Advanced (Gemini-Nano-backed) mode only — this app never falls back to
     * [SpeechRecognizerOptions.Mode.MODE_BASIC], per the hard-block decision.
     * Confirmed against the real `1.0.0-alpha1` classes (decompiled — its
     * javadoc was not directly readable while this was written):
     * `SpeechRecognizerOptions.Builder` has `locale` and `preferredMode`, not
     * a `setLanguage(Language.EN_US)` call as an earlier draft of this file
     * assumed from prose docs alone.
     */
    private fun advancedSpeechOptions(): SpeechRecognizerOptions =
        speechRecognizerOptions {
            locale = Locale.US
            preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        }

    private fun fold(prompt: Int, speech: Int): AiCoreStatus = when {
        prompt == FeatureStatus.UNAVAILABLE || speech == FeatureStatus.UNAVAILABLE ->
            AiCoreStatus.Unsupported
        prompt == FeatureStatus.DOWNLOADING || speech == FeatureStatus.DOWNLOADING ->
            AiCoreStatus.Downloading
        prompt == FeatureStatus.DOWNLOADABLE || speech == FeatureStatus.DOWNLOADABLE ->
            AiCoreStatus.Downloadable { downloadWhicheverIsPending() }
        prompt == FeatureStatus.AVAILABLE && speech == FeatureStatus.AVAILABLE ->
            AiCoreStatus.Available
        else -> AiCoreStatus.Unsupported
    }

    /**
     * Downloads both features that need it and merges their progress into one
     * stream — the settings screen shows one progress bar, not two, because
     * the user does not care which of the two Gemini Nano features is behind.
     */
    private fun downloadWhicheverIsPending(): Flow<DownloadStatus> {
        // Prompt API's download() is the one both features actually share in
        // practice (same underlying Gemini Nano download); Speech Recognition
        // triggers its own if it still reports DOWNLOADABLE after that
        // completes. See refresh() — the caller re-checks status afterward
        // rather than this trying to report a perfectly accurate combined
        // percentage.
        return Generation.getClient().download()
    }
}
