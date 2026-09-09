package com.digitalpet.stt

import android.os.ParcelFileDescriptor
import com.digitalpet.util.DiagnosticLogger
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level speech-to-text service — now ML Kit GenAI Speech Recognition
 * (Gemini Nano, Advanced mode), not Whisper.cpp.
 *
 * **Advanced mode only, no Basic-mode fallback**, consistent with this app's
 * hard-block decision: a device that cannot run Advanced mode is stopped at
 * [com.digitalpet.pet.PetReadiness.DeviceUnsupported] before it ever reaches
 * this class.
 *
 * **The public contract is unchanged on purpose.** Both callers —
 * [com.digitalpet.audio.AudioRecorder]'s phone-mic path (16kHz mono PCM16)
 * and [com.digitalpet.audio.PetVoiceRepository]'s pet-mic/BLE/Opus path
 * (decoded to the same format) — already hand [transcribe] exactly what the
 * new engine needs, so neither caller changed.
 *
 * **Every class and method below is confirmed against the real
 * `1.0.0-alpha1` classes** (decompiled with `javap`, since the alpha's
 * javadoc was not directly readable while this was written) — a first draft
 * of this file guessed a `setLanguage(Language.EN_US)`-style options builder
 * and a callback-based `startRecognition(request, callback)` from prose docs
 * alone, and both were wrong: options take `locale`/`preferredMode`
 * (`SpeechRecognizerOptions.Mode.MODE_ADVANCED`), `AudioSource` lives under
 * `com.google.mlkit.genai.common.audio`, not this package, and
 * `startRecognition` returns a plain `Flow<SpeechRecognizerResponse>` with
 * no callback parameter at all.
 */
@Singleton
class SttService @Inject constructor(
    private val diagnosticLogger: DiagnosticLogger,
) {

    private companion object {
        const val TAG = "SttService"
        const val SAMPLE_RATE_HZ = 16000
        const val BYTES_PER_SAMPLE = 2
    }

    private val client by lazy { SpeechRecognition.getClient(advancedOptions()) }

    /** See [com.digitalpet.pet.AiCoreAvailability.advancedSpeechOptions] — kept in sync manually. */
    private fun advancedOptions(): SpeechRecognizerOptions =
        speechRecognizerOptions {
            locale = Locale.US
            preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        }

    /**
     * Transcribe raw 16-bit mono PCM audio at 16kHz into text.
     *
     * @param pcmInt16 raw PCM audio as signed 16-bit integers at 16 kHz.
     * @return the final transcript, or `""` on failure — the same contract
     *   the Whisper-backed version had, so no caller needed to change.
     */
    suspend fun transcribe(pcmInt16: ShortArray): String = withContext(Dispatchers.Default) {
        val startTime = System.nanoTime()
        diagnosticLogger.log(
            TAG,
            "transcribe — ${pcmInt16.size} samples (${pcmInt16.size / SAMPLE_RATE_HZ.toDouble()}s)"
        )

        var audioFile: File? = null
        try {
            val file = writeHeaderlessPcm(pcmInt16)
            audioFile = file
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val request = speechRecognizerRequest { audioSource = AudioSource.fromPfd(pfd) }

            val result = awaitFinalTranscript(request)

            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            diagnosticLogger.log(TAG, "transcribe completed in ${elapsedMs}ms — \"${result.take(80)}\"")
            result
        } catch (e: Exception) {
            diagnosticLogger.log(TAG, "transcribe error: ${e.message}")
            ""
        } finally {
            audioFile?.delete()
        }
    }

    /**
     * Collects the partial→final response stream down to one final result —
     * this app always wants the finished transcript, never a partial,
     * matching Whisper's old single-shot return shape.
     */
    private suspend fun awaitFinalTranscript(
        request: com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
    ): String {
        var final = ""
        client.startRecognition(request).collect { response ->
            when (response) {
                is SpeechRecognizerResponse.FinalTextResponse -> final = response.text
                is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                // PartialTextResponse: ignored, this app only wants the final
                // transcript. CompletedResponse: end of stream, nothing to do.
                else -> Unit
            }
        }
        return final
    }

    /**
     * Headerless 16-bit PCM, little-endian — [AudioSource.fromPfd]'s stated
     * requirement (Raw, headerless 16-bit PCM; mono; 16kHz).
     */
    private fun writeHeaderlessPcm(samples: ShortArray): File {
        val file = File.createTempFile("stt_", ".pcm")
        FileOutputStream(file).use { out ->
            val buffer = ByteBuffer
                .allocate(samples.size * BYTES_PER_SAMPLE)
                .order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { buffer.putShort(it) }
            out.write(buffer.array())
        }
        return file
    }
}
