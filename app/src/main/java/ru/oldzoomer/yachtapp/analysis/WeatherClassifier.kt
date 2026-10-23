package ru.oldzoomer.yachtapp.analysis

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.support.common.ops.NormalizeOp
import com.google.ai.edge.litert.support.image.ImageProcessor
import com.google.ai.edge.litert.support.image.TensorImage
import com.google.ai.edge.litert.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WeatherClassifier(private val context: Context) {

    private var model: CompiledModel? = null
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
            model = CompiledModel.create(
                context.assets,
                "weather_model.tflite",
                CompiledModel.Options(
                    Accelerator.GPU,
                    Accelerator.CPU
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun classify(bitmap: Bitmap): String? {
        val input = model?.createInputBuffers()
        val output = model?.createOutputBuffers()

        preprocess(bitmap)?.array()?.let { input?.get(0)?.writeInt8(it) }

        if (input != null && output != null) {
            model?.run(input, output)
        }

        val maxIndex = output?.get(0)?.readFloat()?.indices?.maxByOrNull {
            output[0].readFloat()[it]
        } ?: return null
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
        model?.close()
    }
}