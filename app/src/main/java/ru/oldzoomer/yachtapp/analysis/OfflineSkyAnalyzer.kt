package ru.oldzoomer.yachtapp.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ru.oldzoomer.yachtapp.data.Observation
import ru.oldzoomer.yachtapp.data.ThreatLevel
import ru.oldzoomer.yachtapp.data.WeatherAssessment
import kotlin.coroutines.resume

class OfflineSkyAnalyzer(private val context: Context) {

    private val labelerOptions = ImageLabelerOptions.Builder()
        .setConfidenceThreshold(0.35f)
        .build()

    private val labeler: ImageLabeler = ImageLabeling.getClient(labelerOptions)

    suspend fun analyzeObservations(observations: List<Observation>): WeatherAssessment =
        withContext(Dispatchers.IO) {
            if (observations.isEmpty()) {
                return@withContext WeatherAssessment(
                    timestamp = System.currentTimeMillis(),
                    threatLevel = ThreatLevel.CALM,
                    cloudinessOctas = 0,
                    dominantCloudType = "Ясно / Чисто",
                    pressureHpa = 1013.25f,
                    pressureTrend = "Стабильно",
                    squallRiskPercent = 0,
                    estimatedWindKnots = 10,
                    hazards = emptyList(),
                    recommendations = listOf("Погода стабильна. Приятного плавания!"),
                    mlDetectedLabels = emptyList()
                )
            }

            val avgPressure = observations.map { it.pressureHpa }.average().toFloat()
            val avgWind = observations.map { it.userWindKnots }.average().toInt()
            val hasGusts = observations.any { it.userIsGusty }

            var darkPixelsCount = 0
            var totalPixelsSampled = 0
            var maxCloudiness = 0f

            val detectedMlLabelsMap = mutableMapOf<String, Float>()
            val mlSummaryList = mutableListOf<String>()

            for (obs in observations) {
                try {
                    val bitmap =
                        loadDownsampledBitmapFromUri(obs.photoUri.toUri(), maxDimension = 800)
                    if (bitmap != null) {
                        // 1. ML Kit Image Labeling (Offline On-Device ML)
                        val mlLabels = processImageWithMlKit(bitmap)
                        for (label in mlLabels) {
                            val name = label.text
                            val confidence = label.confidence
                            if ((detectedMlLabelsMap[name] ?: 0f) < confidence) {
                                detectedMlLabelsMap[name] = confidence
                            }
                        }

                        // 2. Pixel Luminance & Cloud Cover Analysis
                        val analysis = analyzeBitmap(bitmap)
                        darkPixelsCount += analysis.darkPixels
                        totalPixelsSampled += analysis.totalPixels
                        if (analysis.cloudinessRatio > maxCloudiness) {
                            maxCloudiness = analysis.cloudinessRatio
                        }
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("OfflineSkyAnalyzer", "Error analyzing photo: ${obs.photoUri}", e)
                }
            }

            // Format ML labels summary sorted by confidence
            detectedMlLabelsMap.entries
                .sortedByDescending { it.value }
                .take(6)
                .forEach { entry ->
                    mlSummaryList.add("${entry.key} (${(entry.value * 100).toInt()}%)")
                }

            // Evaluate cloudiness in octas (0-8)
            var cloudinessOctas = (maxCloudiness * 8).toInt().coerceIn(0, 8)
            val darkRatio =
                if (totalPixelsSampled > 0) darkPixelsCount.toFloat() / totalPixelsSampled else 0f

            // Check ML signals for weather indicators
            val mlTextLower = detectedMlLabelsMap.keys.map { it.lowercase() }
            val hasMlStorm =
                mlTextLower.any { it.contains("storm") || it.contains("thunder") || it.contains("lightning") }
            val hasMlClouds =
                mlTextLower.any { it.contains("cloud") || it.contains("overcast") || it.contains("cumulus") }
            val hasMlCumulonimbus =
                mlTextLower.any { it.contains("cumulonimbus") || it.contains("darkness") }

            if (hasMlClouds && cloudinessOctas < 3) {
                cloudinessOctas = 4 // ML detected clouds not caught by brightness alone
            }

            val hazards = mutableListOf<String>()
            val recommendations = mutableListOf<String>()

            var threat = ThreatLevel.CALM
            var squallRisk = 10
            var dominantType = if (hasMlClouds) "Кучевые / Слойчатые (ML)" else "Кучевые (Cu / Sc)"

            if (darkRatio > 0.35f || cloudinessOctas >= 7 || hasMlCumulonimbus) {
                threat = ThreatLevel.WARNING
                squallRisk = 65
                dominantType = "Кучево-дождевые (Cb) / Плотная пелена"
                hazards.add("ML-модель и датчики обнаружили темные грозовые тучи (Cb)")
                recommendations.add("Уменьшите площадь парусности (взять рифы)")
                recommendations.add("Убедитесь, что люки и лючки задраены")
            }

            if (hasMlStorm) {
                threat = ThreatLevel.DANGER_SQUALL
                squallRisk = 85
                hazards.add("ML-модель идентифицировала высокую вероятность штормовых облаков/грозы")
                recommendations.add("Подготовьте штормовой комплект и закрепите страховки")
            }

            if (hasGusts || avgWind > 20) {
                if (threat.ordinal < ThreatLevel.WARNING.ordinal) {
                    threat = ThreatLevel.CAUTION
                }
                squallRisk = maxOf(squallRisk, 75)
                hazards.add("Отмечены порывистый ветер или высокая базовая скорость ($avgWind уз.)")
                recommendations.add("Внимательно следите за поверхностью воды на предмет ряби и «барашков»")
            }

            if ((darkRatio > 0.45f || hasMlStorm) && hasGusts) {
                threat = ThreatLevel.DANGER_SQUALL
                squallRisk = 95
                hazards.add("ВЫСОКИЙ РИСК ШКВАЛА! Темные тучи со стороны фронта + порывистый ветер")
                recommendations.add("СРОЧНО: Уберите стаксель, оставьте минимальный штормовой стаксель или грот")
                recommendations.add("Наденьте страховочные пояса и жилеты, закрепитесь в кокпите")
            }

            if (cloudinessOctas <= 2 && !hasGusts && !hasMlStorm) {
                threat = ThreatLevel.CALM
                squallRisk = 5
                dominantType = "Малооблачно / Ясно"
                recommendations.add("Отличные условия для плавания. Стабильная погода.")
            } else if (threat == ThreatLevel.CALM) {
                threat = ThreatLevel.CAUTION
                squallRisk = 30
                recommendations.add("Переменная облачность. Возможны локальные заходы ветра.")
            }

            val pressureTrendStr = when {
                avgPressure < 1010f -> "Низкое давление (циклонический фронт)"
                avgPressure > 1025f -> "Высокое давление (устойчиво)"
                else -> "Умеренное давление"
            }

            WeatherAssessment(
                timestamp = System.currentTimeMillis(),
                threatLevel = threat,
                cloudinessOctas = cloudinessOctas,
                dominantCloudType = dominantType,
                pressureHpa = avgPressure,
                pressureTrend = pressureTrendStr,
                squallRiskPercent = squallRisk,
                estimatedWindKnots = avgWind,
                hazards = hazards,
                recommendations = recommendations,
                mlDetectedLabels = mlSummaryList
            )
        }

    private suspend fun processImageWithMlKit(bitmap: Bitmap): List<ImageLabel> =
        suspendCancellableCoroutine { continuation ->
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                labeler.process(inputImage)
                    .addOnSuccessListener { labels ->
                        continuation.resume(labels)
                    }
                    .addOnFailureListener { e ->
                        Log.w("OfflineSkyAnalyzer", "ML Kit processing failed", e)
                        continuation.resume(emptyList())
                    }
            } catch (e: Exception) {
                Log.e("OfflineSkyAnalyzer", "Error creating InputImage for ML Kit", e)
                continuation.resume(emptyList())
            }
        }

    private data class BitmapAnalysis(
        val darkPixels: Int,
        val totalPixels: Int,
        val cloudinessRatio: Float
    )

    private fun analyzeBitmap(bitmap: Bitmap): BitmapAnalysis {
        val width = bitmap.width
        val height = (bitmap.height * 0.65f).toInt().coerceAtLeast(1)

        var darkCount = 0
        var brightCount = 0
        val sampleStepX = (width / 20).coerceAtLeast(1)
        val sampleStepY = (height / 20).coerceAtLeast(1)
        var total = 0

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height step sampleStepY) {
            for (x in 0 until width step sampleStepX) {
                val pixel = pixels[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val brightness = (r * 0.299f + g * 0.587f + b * 0.114f)

                total++
                if (brightness < 80f) {
                    darkCount++
                } else if (brightness > 180f) {
                    brightCount++
                }
            }
        }

        val cloudRatio = if (total > 0) (total - brightCount).toFloat() / total else 0.5f
        return BitmapAnalysis(darkCount, total, cloudRatio)
    }

    private fun loadDownsampledBitmapFromUri(uri: Uri, maxDimension: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var inSampleSize = 1
            if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= maxDimension && halfWidth / inSampleSize >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (e: Exception) {
            Log.e("OfflineSkyAnalyzer", "Failed to load downsampled bitmap from $uri", e)
            null
        }
    }
}
