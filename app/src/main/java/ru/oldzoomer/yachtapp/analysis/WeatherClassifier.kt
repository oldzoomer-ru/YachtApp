package ru.oldzoomer.yachtapp.analysis

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.support.common.FileUtil
import com.google.ai.edge.litert.support.common.ops.NormalizeOp
import com.google.ai.edge.litert.support.image.ImageProcessor
import com.google.ai.edge.litert.support.image.TensorImage
import com.google.ai.edge.litert.support.image.ops.ResizeOp
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WeatherClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val labelList = listOf(
        "sunny", "cloudy", "rainy", "foggy",
        "storm", "clear", "snowy", "overcast",
        "hazy", "night"
    )

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "weather_model.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun classify(bitmap: Bitmap): String? {
        val input = preprocess(bitmap) ?: return null
        val output = Array(1) { FloatArray(labelList.size) }
        interpreter?.run(input, output)

        val maxIndex = output[0].indices.maxByOrNull { output[0][it] } ?: return null
        return labelList[maxIndex]
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer? {
        val processor = ImageProcessor.Builder()
            .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 127.5f)) // Нормализация под модель
            .build()

        val tensorImage = processor.process(TensorImage.fromBitmap(bitmap))
        return tensorImage.buffer.apply {
            order(ByteOrder.nativeOrder())
        }
    }

    fun close() {
        interpreter?.close()
    }
}