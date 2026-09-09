package com.digitalpet.data

import com.digitalpet.llm.LlmManager
import com.digitalpet.pet.AiCoreAvailability
import com.digitalpet.pet.AiCoreStatus
import com.digitalpet.pet.PetFaculty
import com.digitalpet.pet.PetReadiness
import com.digitalpet.tts.TtsService
import com.digitalpet.util.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns readiness — not files any more.
 *
 * Every one of the three faculties used to be a file this class tracked and
 * managed: a GGUF path, a Piper `.onnx`+`.json` pair, a Whisper `.bin`. All
 * of that — `availableLlmModels`/`importLlm`/`loadLlm`/`deleteLlm` and their
 * STT and TTS equivalents, `TtsModelPair`, the `last_*_path` preference keys
 * — is gone with the files, not just unused: there is nothing left to import
 * or swap. The LLM and STT are Gemini Nano via AICore now (see
 * [AiCoreAvailability], the one upstream truth both defer to instead of a
 * `.gguf`/`.bin` on disk), and TTS is the platform engine, which needs a
 * locale rather than a model file.
 *
 * What survives is the thing this class was really for underneath the file
 * management: answering "can the pet respond at all" — DESIGN.md §2.1's
 * Axis C — for [com.digitalpet.pet.FirstRun] and the foreground service to
 * read with no screen open. [com.digitalpet.ui.settings.ModelSettingsScreen]
 * (formerly three import/swap cards) is now a single status screen reading
 * this same [readiness].
 */
@Singleton
class ModelRepository @Inject constructor(
    private val llmManager: LlmManager,
    private val ttsService: TtsService,
    private val aiCoreAvailability: AiCoreAvailability,
    private val logger: DiagnosticLogger,
) {

    private companion object {
        const val TAG = "ModelRepository"
    }

    /**
     * TTS init outlives any ViewModel — the same reasoning that used to
     * justify owning file imports past a closed drawer, now just for one
     * startup call instead of user-triggered ones.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Straight from [LlmManager] — Gemini Nano's own state, mirroring [AiCoreAvailability]. */
    val llmState: StateFlow<LlmManager.ModelState> = llmManager.modelState

    /**
     * The raw device-eligibility state — [ModelSettingsScreen][com.digitalpet.ui.settings.ModelSettingsScreen]
     * reads this directly rather than [readiness] to draw the
     * Checking/Downloadable/Downloading/Available/Unsupported states
     * precisely, including a Download action; [readiness] folds all three
     * faculties into one coarser answer for the rest of the app.
     */
    val aiCoreStatus: StateFlow<AiCoreStatus> = aiCoreAvailability.status

    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    /**
     * Whether [com.digitalpet.stt.SttService] can be used — exactly when
     * AICore is [AiCoreStatus.Available], since STT has no readiness of its
     * own to report any more. Exposed separately from [readiness] (which
     * folds all three faculties into one UI-facing answer) because
     * [PetChatViewModel][com.digitalpet.ui.screens.PetChatViewModel]'s
     * record button needs the narrower question: can a recording even be
     * transcribed, not "is the pet fully ready end to end."
     */
    val isSttReady: StateFlow<Boolean> = aiCoreAvailability.status
        .map { it is AiCoreStatus.Available }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Whether the pet can answer at all — see the class doc and DESIGN.md
     * §2.1's Axis C.
     *
     * **STT no longer has a loaded-model name or a per-model error of its
     * own.** Both existed to distinguish "no Whisper .bin present" from "a
     * present one that would not load" — a real distinction when the model
     * was a file. [SttService] has no such state now: it is ready exactly
     * when AICore is, the same gate [llmState] answers to, so its half of
     * [PetReadiness.of]'s signature collapses to one derived value rather
     * than tracking a name/error pair that no longer has two states to tell
     * apart.
     *
     * Eagerly started, same reasoning as before: the window between a
     * foreground-service start and AICore/TTS reporting ready is real, and a
     * lazily-started flow would only begin reporting once something
     * subscribed — backwards for a state the pet needs to speak from with no
     * screen open.
     */
    val readiness: StateFlow<PetReadiness> =
        combine(aiCoreAvailability.status, llmState, _isTtsReady) { aiCore, llm, ttsReady ->
            val sttReady = aiCore is AiCoreStatus.Available
            PetReadiness.of(
                aiCoreStatus = aiCore,
                llm = llm,
                ttsReady = ttsReady,
                sttModelName = "Gemini Nano".takeIf { sttReady },
                sttError = null,
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            // Matches AiCoreStatus.Checking's mapping in PetReadiness.of — the
            // true answer is usually only a dispatch away, not a flash of
            // "unsupported" before the real check has even run once.
            PetReadiness.Loading(PetFaculty.BRAIN)
        )

    init {
        scope.launch { retryTts() }
    }

    /**
     * Re-run the AICore eligibility check — called after a
     * [AiCoreStatus.Downloadable] download completes, since [aiCoreStatus]
     * only updates when [AiCoreAvailability] is told to look again.
     */
    suspend fun refreshAiCoreStatus() = aiCoreAvailability.refresh()

    /**
     * Re-attempt platform TTS initialisation — the Voice card's one action,
     * now that there is no file to re-import. Same path [init]'s block
     * already runs at startup; exposed again for the rare case a phone's
     * TTS engine was mid-update or otherwise transiently unavailable.
     */
    suspend fun retryTts() {
        try {
            ttsService.init()
            _isTtsReady.value = ttsService.isReady
        } catch (e: Exception) {
            logger.log(TAG, "Failed to retry TTS init: ${e.message}")
            _isTtsReady.value = false
        }
    }
}
