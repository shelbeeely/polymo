package com.digitalpet.llm

import android.graphics.Bitmap
import com.digitalpet.pet.AiCoreAvailability
import com.digitalpet.pet.AiCoreStatus
import com.digitalpet.util.DiagnosticLogger
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central manager for on-device LLM inference — now Gemini Nano via the ML
 * Kit GenAI Prompt API, not a loaded GGUF file.
 *
 * **There is no model to point at anymore.** The old [LlmManager] owned a
 * `.gguf` path and a native `llama.cpp` context; this one owns a
 * [GenerativeModel] handle to a system service, and [ModelState] just
 * mirrors [AiCoreAvailability]'s device-eligibility answer plus whatever
 * this class's own calls add on top (a warmup failure, a generation error).
 * There is nothing here to hot-swap, delete, or scan the Downloads folder
 * for — see [com.digitalpet.data.ModelRepository], which lost the matching
 * amount of code.
 */
@Singleton
class LlmManager @Inject constructor(
    private val aiCoreAvailability: AiCoreAvailability,
    private val diagnosticLogger: DiagnosticLogger,
) {

    private companion object {
        const val TAG = "LlmManager"

        /**
         * The Prompt API's own stated ceiling ("Input must be under 4000
         * tokens (or approximately 3000 English words)" — get-started.md).
         * There is no tokenizer on this side of the call, so this is a rough
         * chars-per-token estimate purely to log a warning before a call that
         * would otherwise just fail remotely with no earlier signal.
         */
        const val APPROX_CHARS_PER_TOKEN = 4
        const val TOKEN_CEILING = 4000
    }

    sealed interface ModelState {
        /** Not yet asked. Transient — [AiCoreAvailability] answers within one collect. */
        data object Unloaded : ModelState

        /** Mirrors [AiCoreStatus.Checking]. */
        data object CheckingAvailability : ModelState

        /**
         * Mirrors [AiCoreStatus.Downloadable]/[AiCoreStatus.Downloading].
         * `progress` is `-1f` while the underlying status only says
         * "downloading" without a fraction — the settings screen shows an
         * indeterminate spinner rather than inventing a percentage.
         */
        data class Downloading(val progress: Float) : ModelState

        /** Warmed up and ready for [generate]. */
        data object Ready : ModelState

        /**
         * Either [AiCoreStatus.Unsupported] (though that case is expected to
         * be caught earlier by [com.digitalpet.pet.PetReadiness.DeviceUnsupported]
         * — this exists for defence in depth) or a real generation-time
         * failure. Carries whatever the SDK said, verbatim, same rule as the
         * old native loader errors this replaces.
         */
        data class Error(val message: String) : ModelState
    }

    private val generativeModel: GenerativeModel by lazy { Generation.getClient() }

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Unloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Reactive, not polled: AiCoreAvailability is the one place that
        // calls checkStatus()/download() — this just mirrors its answer, the
        // same relationship ModelRepository used to have with the native
        // loaders except there is now exactly one upstream truth instead of
        // three independent files.
        scope.launch {
            aiCoreAvailability.status.collect { status ->
                _modelState.value = when (status) {
                    is AiCoreStatus.Checking -> ModelState.CheckingAvailability
                    is AiCoreStatus.Downloadable -> ModelState.Downloading(progress = 0f)
                    is AiCoreStatus.Downloading -> ModelState.Downloading(progress = -1f)
                    is AiCoreStatus.Unsupported ->
                        ModelState.Error("This device cannot run Gemini Nano")
                    is AiCoreStatus.Available -> {
                        warmup()
                        ModelState.Ready
                    }
                }
            }
        }
    }

    /**
     * Loads Gemini Nano into memory ahead of the first real call.
     *
     * **Not the old `warmup()`.** `LlamaNative.warmup()` prefilled the KV
     * cache with one specific system prompt, and getting that prompt wrong
     * cost 23 seconds a turn (see the git history on the file this replaced).
     * The Prompt API's `warmup()` has no such trap — it loads the model
     * generically, not a prefix. The prefix itself is [generate]'s job now,
     * via [PromptPrefix] — see that function's doc comment.
     */
    private suspend fun warmup() {
        try {
            generativeModel.warmup()
        } catch (e: Exception) {
            // Non-fatal: generateContentStream() below still works without a
            // successful warmup, just slower on the first call.
            diagnosticLogger.log(TAG, "warmup failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Stream generated text from Gemini Nano.
     *
     * @param prompt       the user's message, plain text — **not** a ChatML
     *   string. There is no template to build any more.
     * @param systemPrompt the persona + the three appended pet rules + the
     *   condition clause, concatenated — see
     *   [com.digitalpet.llm.SystemPromptManager.buildSystemInstruction].
     *   Passed as a [PromptPrefix], **not** as a system-instruction field —
     *   this shipped version of the Prompt API (`1.0.0-beta2`, decompiled to
     *   check) has no such field at all; an earlier draft of this file
     *   assumed one existed from prose docs describing a newer surface. A
     *   prefix is in fact the better fit anyway: this text is genuinely
     *   stable across most turns (only the condition clause moves, and only
     *   when the pet's scores change), which is exactly what prefix caching
     *   is for — real measured gains in Google's own docs (0.82s → 0.45s for
     *   a 300-token prefix on a Pixel 9).
     * @param topP unused — the Prompt API's optional parameters are
     *   `temperature`, `seed`, `topK`, `candidateCount` and `maxOutputTokens`;
     *   there is no topP-equivalent knob to forward this to, confirmed
     *   against the real `GenerateContentRequest.Builder`. Kept in the
     *   signature only so this is not a breaking change for callers that
     *   still pass it.
     * @param image when non-null, sent alongside [prompt] as an [ImagePart] —
     *   the Prompt API's multimodal path
     *   (`GenerateContentRequest.Builder(ImagePart, TextPart)`, confirmed by
     *   decompiling the real `1.0.0-beta2` AAR the same way [PromptPrefix]
     *   was). This is what lets Gemini Nano react to what the Pixel 10's
     *   camera actually saw, not only to the structured detector findings
     *   [com.digitalpet.vision.VisionAnalyzer] folds into [systemPrompt] —
     *   see [com.digitalpet.conversation.PetConversationEngine.describeSight].
     */
    fun generate(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        @Suppress("UNUSED_PARAMETER") topP: Float = 0.9f,
        image: Bitmap? = null,
    ): Flow<String> {
        val approxTokens = (systemPrompt.length + prompt.length) / APPROX_CHARS_PER_TOKEN
        if (approxTokens > TOKEN_CEILING) {
            diagnosticLogger.log(
                TAG,
                "generate — approx $approxTokens tokens exceeds the Prompt API's " +
                    "$TOKEN_CEILING-token ceiling; this call will likely fail remotely"
            )
        }

        val textPart = TextPart(prompt)
        val request = if (image != null) {
            generateContentRequest(ImagePart(image), textPart) {
                this.promptPrefix = PromptPrefix(systemPrompt)
                this.temperature = temperature
                this.maxOutputTokens = maxTokens
            }
        } else {
            generateContentRequest(textPart) {
                this.promptPrefix = PromptPrefix(systemPrompt)
                this.temperature = temperature
                this.maxOutputTokens = maxTokens
            }
        }

        diagnosticLogger.log(
            TAG,
            "generate started — maxTokens=$maxTokens, temp=$temperature, image=${image != null}"
        )

        return generativeModel.generateContentStream(request)
            .map { chunk -> chunk.candidates.firstOrNull()?.text.orEmpty() }
            .catch { e ->
                diagnosticLogger.log(TAG, "generate error: ${e.message}")
                _modelState.value = ModelState.Error(e.message ?: "Unknown error")
                throw e
            }
            .flowOn(Dispatchers.Default)
    }
}
