package ru.oldzoomer.yachtapp.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun CameraPreview(
    cameraManager: CameraManager,
    modifier: Modifier = Modifier,
    onError: (Throwable) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val boundState = remember { mutableStateOf(false) }

    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                if (!boundState.value) {
                    cameraManager.bindCamera(
                        lifecycleOwner = lifecycleOwner,
                        previewView = this,
                        onError = onError,
                    )
                    boundState.value = true
                }
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
