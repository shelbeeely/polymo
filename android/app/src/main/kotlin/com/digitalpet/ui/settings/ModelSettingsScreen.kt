package com.digitalpet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitalpet.pet.AiCoreStatus
import com.digitalpet.ui.components.core.Badge
import com.digitalpet.ui.components.settings.SlotCard
import com.digitalpet.ui.screens.PetChatViewModel
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import kotlinx.coroutines.launch

/**
 * AICore status — what three import cards collapsed into once there were no
 * more files to import.
 *
 * **Every model-swap affordance this screen used to have is gone with the
 * files.** Brain, Ears and Voice were each a `.gguf`/`.bin`/`.onnx` pair the
 * user fetched and pointed the app at; the LLM and STT are Gemini Nano via
 * AICore now (one eligibility check, not three files) and TTS is the
 * platform engine (a locale, not a model). There is nothing left to browse,
 * pick, or delete.
 *
 * **[SlotCard] keeps exactly one caller here, not three.** The design
 * system still names it as a shared component — [AiCoreCard] below is a
 * genuine fit for it: a real "fetch this to make the faculty work" action,
 * same shape as the old import flow, just downloading a feature instead of
 * copying a picked file. Voice has no equivalent action worth forcing into
 * that shape (there is nothing to fetch — the platform engine is already
 * installed), so it gets a plainer status line instead of a second
 * `SlotCard` with an inert import row.
 */
@Composable
fun ModelSettingsScreen(
    padding: PaddingValues,
    viewModel: PetChatViewModel = hiltViewModel(),
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PetSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.s10)
    ) {
        AiCoreCard(viewModel)
        VoiceStatus(viewModel)
        Text(
            "The pet needs all three: it hears you and thinks through Gemini Nano, " +
                "and speaks with your phone's own voice. Either one missing and it " +
                "goes quiet.",
            style = MaterialTheme.typography.bodySmall,
            fontSize = PetTextSize.t11_5,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PetSpacing.s6, bottom = PetSpacing.scrollBottom)
        )
    }
}

/**
 * Brain + Ears, combined — both are the same AICore eligibility check
 * ([com.digitalpet.pet.AiCoreAvailability] folds the Prompt API and Speech
 * Recognition Advanced mode into one status), so showing them as two cards
 * that always move in lockstep would just be the same fact printed twice.
 */
@Composable
private fun AiCoreCard(viewModel: PetChatViewModel) {
    val status by viewModel.aiCoreStatus.collectAsState()
    var downloading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SlotCard(
        icon = Icons.Default.Psychology,
        title = "Brain & Ears",
        badge = when (status) {
            is AiCoreStatus.Available -> Badge.Loaded
            is AiCoreStatus.Checking -> Badge.Working("checking")
            is AiCoreStatus.Downloading -> Badge.Working("downloading")
            is AiCoreStatus.Downloadable -> Badge.None
            is AiCoreStatus.Unsupported -> Badge.Failed
        },
        detail = when (status) {
            is AiCoreStatus.Available ->
                "Ready — Gemini Nano is powering the pet's brain and ears."
            is AiCoreStatus.Checking -> "Checking whether this phone supports Gemini Nano…"
            is AiCoreStatus.Downloading -> "Downloading Gemini Nano…"
            is AiCoreStatus.Downloadable ->
                "Gemini Nano is not downloaded yet — the pet cannot think or hear " +
                    "you until it is."
            is AiCoreStatus.Unsupported ->
                "This phone cannot run Gemini Nano. AICore requires a Pixel 10 or " +
                    "Pixel 11 today — there is nothing to add or download that changes " +
                    "that."
        },
        importLabel = "Download Gemini Nano",
        importing = downloading,
        onImport = {
            val downloadable = status as? AiCoreStatus.Downloadable
            if (downloadable != null) {
                downloading = true
                scope.launch {
                    try {
                        downloadable.download().collect { /* progress surfaces via badge/detail above */ }
                    } finally {
                        viewModel.refreshAiCoreStatus()
                        downloading = false
                    }
                }
            }
        },
        othersLabel = "About AICore",
    ) {
        Text(
            "Speech Recognition Advanced mode supports Pixel 10 and Pixel 11 " +
                "today; more devices are expected over time.",
            style = MaterialTheme.typography.bodySmall,
            fontSize = PetTextSize.t11,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceStatus(viewModel: PetChatViewModel) {
    val ready by viewModel.isTtsReady.collectAsState()

    SlotCard(
        icon = Icons.Default.RecordVoiceOver,
        title = "Voice",
        badge = if (ready) Badge.Loaded else Badge.Failed,
        detail = if (ready) {
            "Ready — using your phone's own on-device voice."
        } else {
            "No on-device voice is available on this phone."
        },
        importLabel = "Retry",
        importing = false,
        onImport = { viewModel.retryTts() },
        othersLabel = "About the pet's voice",
    ) {
        Text(
            "Gemini Nano has no speech-synthesis capability, so the pet's voice " +
                "is your phone's own platform text-to-speech engine, not AICore.",
            style = MaterialTheme.typography.bodySmall,
            fontSize = PetTextSize.t11,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
