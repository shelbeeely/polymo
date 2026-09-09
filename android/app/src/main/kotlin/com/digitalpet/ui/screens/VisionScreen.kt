package com.digitalpet.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.digitalpet.ui.components.core.Card
import com.digitalpet.ui.components.core.PetButton
import com.digitalpet.ui.components.core.PetButtonSecondary
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.vision.DocumentScanner
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * "Show the pet something" — the Pixel 10's own camera as a vision source.
 *
 * **The UX distinction the plan calls for.** This is explicitly the user's
 * phone camera, pointed at whatever they choose, not the pet's own senses —
 * there is no pet-onboard camera yet (that is phases 9/10, both still
 * blocked on hardware that does not exist). A button plainly reading "Show
 * the pet something" rather than any wording implying the pet is looking
 * through its own eyes is the whole fix for that ambiguity; no separate
 * mode switch is needed until a second camera source actually exists.
 *
 * **No new design-system component.** A camera preview surface is platform
 * content, the same category as the BLE scan list — this screen composes
 * existing `Card`/`PetButton` components around it rather than inventing a
 * roster entry `ComponentRosterTest` would then have to know about.
 */
@Composable
fun VisionScreen(
    chatViewModel: PetChatViewModel,
    padding: PaddingValues,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isGenerating by chatViewModel.isGenerating.collectAsState()
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    val documentScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        DocumentScanner.pageCountFrom(result.data)?.let { pageCount ->
            chatViewModel.acknowledgeDocumentScan(pageCount)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = PetSpacing.s16, vertical = PetSpacing.s12),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.s12),
    ) {
        if (!hasCameraPermission) {
            Card {
                Text(
                    "PolyMO needs the camera to show the pet what this phone sees.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PetButton(
                    onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.padding(top = PetSpacing.s12),
                ) { Text("Allow camera") }
            }
        } else {
            var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
            // Captured here, in composition, and merely READ inside the
            // AndroidView factory below — that factory lambda runs outside
            // composition, where a `@Composable` accessor cannot be called.
            val lifecycleOwner = LocalLifecycleOwner.current

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(PetRadius.r20)),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener(
                        {
                            val provider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val capture = ImageCapture.Builder().build()
                            imageCapture = capture
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture,
                            )
                        },
                        ContextCompat.getMainExecutor(ctx),
                    )
                    previewView
                },
            )

            PetButton(
                onClick = {
                    val capture = imageCapture ?: return@PetButton
                    capture.takePicture(
                        directExecutor(),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                // ImageProxy.toBitmap() (camera-core, confirmed by
                                // decompiling it) decodes the JPEG and nothing
                                // more — no rotation applied — so rotationDegrees
                                // still has to travel separately down to
                                // InputImage.fromBitmap in VisionAnalyzer.
                                val bitmap = image.toBitmap()
                                val rotation = image.imageInfo.rotationDegrees
                                image.close()
                                scope.launch { chatViewModel.describeSight(bitmap, rotation) }
                            }
                        },
                    )
                },
                enabled = !isGenerating,
            ) { Text(if (isGenerating) "The pet is thinking…" else "Show the pet") }

            PetButtonSecondary(
                onClick = {
                    val act = activity ?: return@PetButtonSecondary
                    scope.launch {
                        val sender = DocumentScanner.startScanIntentSender(act)
                        documentScanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                },
            ) { Text("Scan a document") }
        }
    }
}

/** A same-thread executor — the capture callback only rotates/decodes a small JPEG. */
private fun directExecutor(): Executor = Executor { it.run() }
