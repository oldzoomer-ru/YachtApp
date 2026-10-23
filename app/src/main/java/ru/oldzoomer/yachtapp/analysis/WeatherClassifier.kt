package ru.oldzoomer.yachtapp.analysis

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.support.common.ops.NormalizeOp
import com.google.ai.edge.litert.support.image.ImageProcessor
import com.google.ai.edge.litert.support.image.TensorImage
import com.google.ai.edge.litert.support.image.ops.ResizeOp

data class WeatherResult(
    val label: String,
    val confidence: Float
)

class WeatherClassifier(private val context: Context) {

    private var model: CompiledModel? = null

    private val labelList = listOf(
        "sunny", "cloudy", "rainy", "foggy",
        "storm", "clear", "snowy", "overcast",
        "hazy", "night"
    )

    // Reused image processor instance
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(127.5f, 127.5f))
        .build()

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            model = CompiledModel.create(
                context.assets,
                "weather_model.tflite",
                CompiledModel.Options(
                    Accelerator.GPU,
                    Accelerator.CPU
                )
            )
        } catch (e: Exception) {
            Log.e("WeatherClassifier", "Failed to compile LiteRT model", e)
        }
    }

    /**
     * Classifies bitmap and returns WeatherResult with label and confidence,
     * or null if confidence is below minConfidence.
     */
    fun classify(bitmap: Bitmap, minConfidence: Float = 0.45f): WeatherResult? {
        val currentModel = model ?: return null

        return try {
            val inputBuffers = currentModel.createInputBuffers()
            val outputBuffers = currentModel.createOutputBuffers()

            // 1. Image preprocessing into Float32 buffer
            val tensorImage = imageProcessor.process(TensorImage.fromBitmap(bitmap))
            val floatArray = tensorImage.tensorBuffer.floatArray

            // Write float array into LiteRT input buffer
            inputBuffers[0].writeFloat(floatArray)

            // 2. Inference
            currentModel.run(inputBuffers, outputBuffers)

            // 3. Read probabilities array once
            val probabilities: FloatArray = outputBuffers[0].readFloat()
            if (probabilities.isEmpty()) return null

            // 4. ArgMax search
            var maxIdx = 0
            var maxProb = probabilities[0]
            for (i in 1 until probabilities.size) {
                if (probabilities[i] > maxProb) {
                    maxProb = probabilities[i]
                    maxIdx = i
                }
            }

            // Filter out low confidence or out-of-bounds indices
            if (maxProb < minConfidence || maxIdx !in labelList.indices) {
                return null
            }

            WeatherResult(
                label = labelList[maxIdx],
                confidence = maxProb
            )
        } catch (e: Exception) {
            Log.e("WeatherClassifier", "Error running classification", e)
            null
        }
    }

    fun close() {
        try {
            model?.close()
            model = null
        } catch (e: Exception) {
            Log.e("WeatherClassifier", "Error closing model", e)
        }
    }
}
