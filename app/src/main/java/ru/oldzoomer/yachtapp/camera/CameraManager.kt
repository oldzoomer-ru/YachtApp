package ru.oldzoomer.yachtapp.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.core.ImageCapture.OutputFileResults
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /**
     * Binds the ProcessCameraProvider and sets up the Preview and ImageCapture use cases
     * for a given PreviewView.
     */
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onInitialized: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val newImageCapture = ImageCapture.Builder()
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .build()

                provider.unbindAll()

                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    newImageCapture
                )

                imageCapture = newImageCapture
                Log.d("CameraManager", "Camera bound successfully")
                onInitialized()
            } catch (e: Exception) {
                Log.e("CameraManager", "Failed to bind camera to lifecycle", e)
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Captures a photo and saves it to internal storage in `captured_images/`.
     * Returns a Flow emitting the Result with the saved Uri.
     */
    fun takePhoto(): Flow<Result<Uri>> = callbackFlow {
        val capture = imageCapture
        if (capture == null) {
            trySend(Result.failure(IllegalStateException("Camera is not ready or ImageCapture is not initialized")))
            close()
            return@callbackFlow
        }

        val directory = File(context.filesDir, "captured_images")
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val photoFile = File(
            directory,
            "IMG_${
                SimpleDateFormat(
                    "yyyyMMdd_HHmmssSSS",
                    Locale.US
                ).format(System.currentTimeMillis())
            }.jpg"
        )
        val outputFileOptions = OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputFileOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    trySend(Result.success(savedUri))
                    close()
                }

                override fun onError(imageCaptureException: ImageCaptureException) {
                    trySend(Result.failure(imageCaptureException))
                    close()
                }
            }
        )

        awaitClose { }
    }

    fun unbind() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e("CameraManager", "Error unbinding camera", e)
        }
    }

    fun release() {
        unbind()
        try {
            if (!executor.isShutdown) {
                executor.shutdown()
            }
        } catch (e: Exception) {
            Log.e("CameraManager", "Error shutting down camera executor", e)
        }
    }
}
