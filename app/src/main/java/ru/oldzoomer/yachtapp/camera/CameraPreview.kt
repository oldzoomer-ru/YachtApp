package ru.oldzoomer.yachtapp.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun CameraPreview(
    cameraManager: CameraManager,
    modifier: Modifier = Modifier,
    onError: (Throwable) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                cameraManager.bindCamera(
                    lifecycleOwner = lifecycleOwner,
                    previewView = this,
                    onError = onError
                )
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
