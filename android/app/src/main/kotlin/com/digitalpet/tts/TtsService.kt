package com.digitalpet.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.digitalpet.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * High-level text-to-speech service — now the platform `TextToSpeech`
 * engine, not Piper.
 *
 * **Per the user's explicit choice**, this is not a Gemini/AICore API:
 * Gemini Nano has no speech-synthesis capability at all (confirmed against
 * two separate doc fetches while planning this swap), so the pet's voice is
 * whatever on-device neural voice the platform ships — Pixel's own, in this
 * app's case — rather than something AICore-gated.
 *
 * **There is no model file to load or espeak-ng phonemizer data to unpack
 * any more.** Piper needed both; the platform engine is already installed
 * and configured by the OS. What replaces `init(modelPath, configPath)` is
 * just picking a language.
 *
 * **`synthesize()`'s only awkward part**: Android's `TextToSpeech` has no
 * "give me raw PCM back" call — the practical way to get samples out is
 * `synthesizeToFile()` to a WAV, then decode the WAV. [Resampler] and
 * [com.digitalpet.audio.OpusCodec] downstream are unaffected: they already
 * take the source sample rate as a parameter rather than assuming Piper's
 * 22050 Hz, so whatever this engine's voice actually emits (commonly 22050
 * or 24000 Hz) flows through the same resample-to-16kHz path Piper used.
 */
@Singleton
class TtsService @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val diagnosticLogger: DiagnosticLogger
) {

    private companion object {
        const val TAG = "TtsService"

        // Keep letters, marks, numbers, punctuation, spaces, and newlines.
        // Strip everything else (emoji, symbols) — the platform engine
        // chokes on these exactly the way Piper did.
        val EMOJI_REGEX = Regex("[^\\p{L}\\p{M}\\p{N}\\p{P}\\p{Z}\\n]")
    }

    private var tts: TextToSpeech? = null
    private var isInitialised = false
    val isReady: Boolean get() = isInitialised

    // Serialises init/synthesize/destroy the same way nativeMutex did for
    // the JNI calls — TextToSpeech's utterance-progress listener is set once
    // per instance, so overlapping synthesize() calls would race on it.
    private val mutex = Mutex()

    private var lastSampleRateHz = 0

    /**
     * Initialise the platform TTS engine for the given [locale].
     *
     * Replaces the old `init(context, modelPath, configPath)` — there is
     * nothing to point at on disk any more, just a language to request.
     */
    suspend fun init(locale: Locale = Locale.US) = mutex.withLock {
        val startTime = System.nanoTime()
        diagnosticLogger.log(TAG, "init — locale=$locale")

        val initResult = suspendCancellableCoroutine<Int> { cont ->
            tts = TextToSpeech(appContext) { status ->
                if (cont.isActive) cont.resume(status)
            }
        }

        if (initResult != TextToSpeech.SUCCESS) {
            diagnosticLogger.log(TAG, "TextToSpeech engine init failed — status=$initResult")
            isInitialised = false
            return@withLock
        }

        val langResult = tts?.setLanguage(locale)
        isInitialised = langResult != TextToSpeech.LANG_MISSING_DATA &&
            langResult != TextToSpeech.LANG_NOT_SUPPORTED

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        if (isInitialised) {
            diagnosticLogger.log(TAG, "init complete in ${elapsedMs}ms")
        } else {
            diagnosticLogger.log(TAG, "init — locale $locale unavailable (langResult=$langResult)")
        }
    }

    /**
     * Synthesise [text] to 16-bit PCM samples at whatever rate the engine's
     * active voice emits — see [getSampleRate].
     *
     * @return the PCM samples, or an empty array if [text] is blank after
     *   cleaning, or on any synthesis failure.
     */
    suspend fun synthesize(text: String): ShortArray = withContext(Dispatchers.Default) {
        val cleanText = text.replace(EMOJI_REGEX, " ").replace(Regex("\\s+"), " ").trim()
        if (cleanText.isBlank()) return@withContext ShortArray(0)

        mutex.withLock {
            check(isInitialised) { "TtsService not initialised — call init() first" }
            val engine = checkNotNull(tts)

            val startTime = System.nanoTime()
            diagnosticLogger.log(TAG, "synthesize — \"${cleanText.take(60)}\" (${cleanText.length} chars)")

            val file = File.createTempFile("tts_", ".wav")
            try {
                val utteranceId = UUID.randomUUID().toString()

                val synthesisResult = suspendCancellableCoroutine<Boolean> { cont ->
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit

                        override fun onDone(id: String?) {
                            if (id == utteranceId && cont.isActive) cont.resume(true)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId && cont.isActive) cont.resume(false)
                        }

                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId && cont.isActive) cont.resume(false)
                        }
                    })

                    val params = Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }
                    val queued = engine.synthesizeToFile(cleanText, params, file, utteranceId)
                    if (queued != TextToSpeech.SUCCESS && cont.isActive) {
                        cont.resume(false)
                    }
                }

                if (!synthesisResult) {
                    diagnosticLogger.log(TAG, "synthesize failed")
                    return@withLock ShortArray(0)
                }

                val (sampleRate, pcm) = readWavPcm16(file)
                lastSampleRateHz = sampleRate

                val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                val durationSec = if (sampleRate > 0) pcm.size.toFloat() / sampleRate else 0f
                diagnosticLogger.log(
                    TAG,
                    "synthesize complete in ${elapsedMs}ms — ${pcm.size} samples " +
                        "(~${"%.1f".format(durationSec)}s @ ${sampleRate}Hz)"
                )

                pcm
            } catch (e: Exception) {
                diagnosticLogger.log(TAG, "synthesize error: ${e.message}")
                ShortArray(0)
            } finally {
                file.delete()
            }
        }
    }

    /**
     * The sample rate of the most recent [synthesize] call's output.
     *
     * Read from that call's WAV header rather than queried from the engine
     * ahead of time — [android.speech.tts.TextToSpeech] has no "sample rate
     * for the active voice" call, and different voices can differ, so the
     * header is the only source of truth actually available.
     */
    fun getSampleRate(): Int = lastSampleRateHz

    fun destroy() {
        diagnosticLogger.log(TAG, "destroy")
        tts?.shutdown()
        tts = null
        isInitialised = false
    }

    /**
     * Read a PCM16 WAV file's sample rate and samples.
     *
     * Scans chunks rather than assuming a fixed 44-byte header, since a
     * `data` chunk is not guaranteed to be the first one after `fmt `.
     */
    private fun readWavPcm16(file: File): Pair<Int, ShortArray> {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a RIFF file" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAVE file" }

            var sampleRate = 0
            val chunkId = ByteArray(4)
            val sizeBuf = ByteArray(4)

            while (raf.filePointer < raf.length()) {
                raf.readFully(chunkId)
                raf.readFully(sizeBuf)
                val chunkSize = ByteBuffer.wrap(sizeBuf).order(ByteOrder.LITTLE_ENDIAN).int
                val id = String(chunkId, Charsets.US_ASCII)

                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize)
                        raf.readFully(fmt)
                        sampleRate = ByteBuffer.wrap(fmt, 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).int
                    }
                    "data" -> {
                        val data = ByteArray(chunkSize)
                        raf.readFully(data)
                        val samples = ShortArray(chunkSize / 2)
                        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(samples)
                        return sampleRate to samples
                    }
                    else -> raf.skipBytes(chunkSize)
                }
                // Chunks are word-aligned; skip the pad byte on an odd size.
                if (chunkSize % 2 == 1 && raf.filePointer < raf.length()) raf.skipBytes(1)
            }
            return sampleRate to ShortArray(0)
        }
    }
}
