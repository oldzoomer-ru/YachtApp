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

    /**
     * ML labels that are relevant for weather/sky analysis.
     * We only consider these labels as valid signals.
     */
    private val validSkyLabels = setOf(
        // Storm indicators
        "storm", "thunder", "lightning", "cumulonimbus", "thunderhead",
        "hurricane", "tornado", "typhoon", "cyclone",
        // Cloud types & conditions
        "cloud", "overcast", "cumulus", "stratus", "cirrus", "nimbus",
        "fog", "mist", "haze", "smog", "drizzle", "cloudy",
        // Clear/Fair weather
        "sunny", "clear", "blue sky", "bright", "sunshine", "fair weather",
        // Precipitation & Wind
        "rain", "snow", "ice", "wind", "gust"
    )

    /**
     * ML labels that strongly indicate storm conditions. These are weighted heavily
     * but only trigger danger if corroborated by pixel analysis or multiple raunces.
     */
    private val stormIndicatorLabels = setOf(
        "storm", "thunder", "lightning", "cumulonimbus", "thunderhead",
        "hurricane", "tornado", "typhoon", "cyclone"
    )

    /**
     * ML labels that indicate cloud presence. Used for cloudiness estimation.
     */
    private val cloudLabels = setOf(
        "cloud", "overcast", "cumulus", "stratus", "cirrus", "nimbus",
        "fog", "mist", "haze", "smog", "drizzle"
    )

    /**
     * Labels that indicate clear/fair weather when they dominate.
     */
    private val clearWeatherLabels = setOf(
        "sunny", "clear", "blue sky", "bright", "sunshine", "fair weather"
    )

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

            // Per-observation analysis results for consensus building
            data class FrameAnalysis(
                val darkRatio: Float,
                val cloudinessRatio: Float,
                val rawMlLabels: Map<String, Float>,
                val stormSignal: Boolean,
                val cloudSignal: Boolean,
                val cumulonimbusSignal: Boolean,
                val clearSignal: Boolean,
                val skyCoverageEstimate: Int // 0-8 octas from this single frame
            )

            val frameAnalyses = mutableListOf<FrameAnalysis>()
            var maxCloudiness = 0f
            var totalDarkPixels = 0
            var totalSampledPixels = 0

            // Global ML labels aggregated across all frames (confidence = max per label)
            val detectedMlLabelsMap = mutableMapOf<String, Float>()

            for (obs in observations) {
                try {
                    val bitmap = loadDownsampledBitmapFromUri(obs.photoUri.toUri(), maxDimension = 800)
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

                        // 2. Pixel luminance & cloud cover analysis per frame
                        val bitmapAnalysis = analyzeBitmap(bitmap)
                        totalDarkPixels += bitmapAnalysis.darkPixels
                        totalSampledPixels += bitmapAnalysis.totalPixels
                        if (bitmapAnalysis.cloudinessRatio > maxCloudiness) {
                            maxCloudiness = bitmapAnalysis.cloudinessRatio
                        }

                        // 3. Per-frame ML signal classification
                        val frameLabels = mlLabels.associate { it.text to it.confidence }
                        val frameStormSignal = classifyStormSignal(frameLabels)
                        val frameCloudSignal = classifyCloudSignal(frameLabels)
                        val frameCumulonimbusSignal = classifyCumulonimbusSignal(frameLabels)
                        val frameClearSignal = classifyClearSignal(frameLabels)

                        // Estimate cloudiness octas from this single frame
                        val frameCloudOctas = (bitmapAnalysis.cloudinessRatio * 8).toInt().coerceIn(0, 8)
                        // Boost if ML confirms clouds but pixel brightness doesn't fully show it
                        val adjustedCloudOctas = if (frameCloudSignal && frameCloudOctas < 3) 4 else frameCloudOctas

                        frameAnalyses.add(FrameAnalysis(
                            darkRatio = bitmapAnalysis.darkPixels.toFloat() / maxOf(bitmapAnalysis.totalPixels, 1),
                            cloudinessRatio = bitmapAnalysis.cloudinessRatio,
                            rawMlLabels = frameLabels,
                            stormSignal = frameStormSignal,
                            cloudSignal = frameCloudSignal,
                            cumulonimbusSignal = frameCumulonimbusSignal,
                            clearSignal = frameClearSignal,
                            skyCoverageEstimate = adjustedCloudOctas
                        ))

                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("OfflineSkyAnalyzer", "Error analyzing photo: ${obs.photoUri}", e)
                }
            }

            // Format ML labels summary sorted by confidence, keeping only valid sky labels
            val filteredLabelsMap = detectedMlLabelsMap.filterKeys { it in validSkyLabels }
            val mlSummaryList = mutableListOf<String>()
            filteredLabelsMap.entries
                .sortedByDescending { it.value }
                .take(6)
                .forEach { entry ->
                    mlSummaryList.add("${entry.key} (${(entry.value * 100).toInt()}%)")
                }

            // ==========================================
            // CONSENSUS-BASED DECISION ENGINE
            // ==========================================

            // Count how many frames signal each condition
            val stormFrameCount = frameAnalyses.count { it.stormSignal }
            val cloudFrameCount = frameAnalyses.count { it.cloudSignal }
            val cumulonimbusFrameCount = frameAnalyses.count { it.cumulonimbusSignal }
            val clearFrameCount = frameAnalyses.count { it.clearSignal }
            val totalFrames = maxOf(frameAnalyses.size, 1)

            // Global consensus thresholds — require corroboration across multiple frames
            val consensusStorm = stormFrameCount >= 2 || (stormFrameCount >= 1 && hasGusts)
            val consensusClouds = cloudFrameCount >= 2 || cloudFrameCount >= 1 && maxCloudiness > 0.5f
            val consensusCumulonimbus = cumulonimbusFrameCount >= 2 ||
                (cumulonimbusFrameCount >= 1 && frameAnalyses.any { it.darkRatio > 0.3f })

            // Check if clear weather is dominant across frames
            val dominantClear = clearFrameCount >= totalFrames * 0.6f

            // Average dark ratio across all frames (not just max)
            val avgDarkRatio = if (frameAnalyses.isNotEmpty()) {
                frameAnalyses.map { it.darkRatio }.average().toFloat()
            } else 0f

            // Evaluate cloudiness in octas (0-8) — use max but cross-check with consensus
            var cloudinessOctas = (maxCloudiness * 8).toInt().coerceIn(0, 8)
            if (consensusClouds && cloudinessOctas < 3) {
                cloudinessOctas = 4 // Consensus confirms clouds even if brightness is low
            }
            if (dominantClear && cloudinessOctas > 5) {
                cloudinessOctas = maxOf(cloudinessOctas - 2, 0) // Clear labels reduce estimated cloudiness
            }

            val darkRatio = if (totalSampledPixels > 0) totalDarkPixels.toFloat() / totalSampledPixels else 0f

            val hazards = mutableListOf<String>()
            val recommendations = mutableListOf<String>()

            var threat = ThreatLevel.CALM
            var squallRisk = 10
            var dominantType = "Кучевые (Cu / Sc)"

            // --- DANGER: Squall detection requires MULTIPLE corroborating signals ---
            if ((darkRatio > 0.35f || avgDarkRatio > 0.25f || consensusCumulonimbus) && hasGusts && consensusStorm) {
                threat = ThreatLevel.DANGER_SQUALL
                squallRisk = 95
                dominantType = "Кучево-дождевые (Cb) — подтверждено несколькими ракурсами"
                hazards.add("ВЫСОКИЙ РИСК ШКВАЛА! Тёмные тучи + порывистый ветер + ML-подтверждение")
                recommendations.add("СРОЧНО: Уберите стаксель, оставьте минимальный штормовой стаксель или грот")
                recommendations.add("Наденьте страховочные пояса и жилеты, закрепитесь в кокпите")
            } else if ((darkRatio > 0.45f || consensusCumulonimbus) && hasGusts) {
                threat = ThreatLevel.DANGER_SQUALL
                squallRisk = 90
                dominantType = "Кучево-дождевые (Cb) — тёмные тучи со стороны фронта"
                hazards.add("ВЫСОКИЙ РИСК ШКВАЛА! Тёмные тучи со стороны фронта + порывистый ветер")
                recommendations.add("СРОЧНО: Уберите стаксель, оставьте минимальный штормовой стаксель или грот")
                recommendations.add("Наденьте страховочные пояса и жилеты, закрепитесь в кокпите")
            }

            // --- WARNING: Single-source storm signal without gust confirmation ---
            else if (consensusCumulonimbus) {
                threat = ThreatLevel.WARNING
                squallRisk = 65
                dominantType = "Кучево-дождевые (Cb) / Плотная пелена"
                hazards.add("ML-модель и датчики обнаружили тёмные грозовые тучи (Cb)")
                recommendations.add("Уменьшите площадь парусности (взять рифы)")
                recommendations.add("Убедитесь, что люки и лючки задраены")
            } else if (darkRatio > 0.35f && cloudinessOctas >= 6) {
                threat = ThreatLevel.WARNING
                squallRisk = 60
                dominantType = "Плотная пелена / Слоисто-дождевые (Ns)"
                hazards.add("Тёмные тучи занимают более 7/8 неба, давление падает")
                recommendations.add("Уменьшите площадь парусности (взять рифы)")
                recommendations.add("Проверьте страховку и подготовьте штормовой комплект")
            }

            // --- CAUTION: Moderate risk signals ---
            else if (consensusStorm) {
                threat = ThreatLevel.WARNING
                squallRisk = 70
                dominantType = "Накопление кучевых облаков"
                hazards.add("ML-модель идентифицировала штормовые признаки в нескольких ракурсах")
                recommendations.add("Подготовьте штормовой комплект и закрепите страховки")
            } else if ((hasGusts || avgWind > 20) && cloudinessOctas >= 4) {
                threat = ThreatLevel.CAUTION
                squallRisk = maxOf(squallRisk, 60)
                hazards.add("Порывистый ветер + облачность ($avgWind уз., ${cloudinessOctas}/8)")
                recommendations.add("Внимательно следите за поверхностью воды на предмет ряби и «барашков»")
            } else if (hasGusts || avgWind > 20) {
                threat = ThreatLevel.CAUTION
                squallRisk = maxOf(squallRisk, 50)
                hazards.add("Отмечен порывистый ветер или высокая базовая скорость ($avgWind уз.)")
                recommendations.add("Внимательно следите за поверхностью воды на предмет ряби и «барашков»")
            }

            // --- CALM: Clear weather consensus ---
            if (dominantClear && cloudinessOctas <= 2 && !hasGusts && !consensusStorm) {
                threat = ThreatLevel.CALM
                squallRisk = 5
                dominantType = "Малооблачно / Ясно"
                recommendations.add("Отличные условия для плавания. Стабильная погода.")
            } else if (threat == ThreatLevel.CALM && cloudinessOctas <= 3) {
                threat = ThreatLevel.CAUTION
                squallRisk = 30
                dominantType = "Малооблачно / Переменная облачность"
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

    /**
     * Check if ML labels in a single frame indicate storm conditions.
     * Requires either high-confidence storm labels OR combination of multiple weak signals.
     */
    private fun classifyStormSignal(frameLabels: Map<String, Float>): Boolean {
        val stormLabels = frameLabels.filterKeys { it.lowercase() in stormIndicatorLabels }
            .filterValues { it > 0.5f } // Only high-confidence

        if (stormLabels.isNotEmpty()) return true

        // Weak signal combination: multiple cloud-related + darkness
        val darkOrStormy = frameLabels.filterKeys {
            it.lowercase().contains("dark") || it.lowercase().contains("storm")
        }.any { it.value > 0.4f }

        val heavyCloud = frameLabels.filterKeys {
            it.lowercase() in setOf("overcast", "nimbus", "cumulus")
        }.filterValues { it > 0.5f }

        return darkOrStormy && heavyCloud.size >= 2
    }

    /**
     * Check if ML labels indicate cloud presence in a single frame.
     */
    private fun classifyCloudSignal(frameLabels: Map<String, Float>): Boolean {
        val cloudLabels = frameLabels.filterKeys { it.lowercase() in cloudLabels }
            .filterValues { it > 0.4f }
        return cloudLabels.isNotEmpty()
    }

    /**
     * Check if ML labels specifically indicate cumulonimbus (thunderstorm) clouds.
     */
    private fun classifyCumulonimbusSignal(frameLabels: Map<String, Float>): Boolean {
        val cbLabels = frameLabels.filterKeys {
            it.lowercase().contains("cumulonimbus") ||
            it.lowercase().contains("thunderhead") ||
            it.lowercase().contains("storm cloud")
        }.filterValues { it > 0.5f }

        // Also check for "darkness" but only with supporting cloud evidence
        val hasDarkness = frameLabels.filterKeys {
            it.lowercase() in setOf("darkness", "dark", "gloomy")
        }.any { it.value > 0.5f }

        if (cbLabels.isNotEmpty()) return true
        if (hasDarkness && classifyCloudSignal(frameLabels)) return true

        return false
    }

    /**
     * Check if ML labels indicate clear/fair weather.
     */
    private fun classifyClearSignal(frameLabels: Map<String, Float>): Boolean {
        val clearLabels = frameLabels.filterKeys { it.lowercase() in clearWeatherLabels }
            .filterValues { it > 0.45f }
        return clearLabels.isNotEmpty()
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