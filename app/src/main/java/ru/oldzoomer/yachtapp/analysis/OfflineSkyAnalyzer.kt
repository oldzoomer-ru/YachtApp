package ru.oldzoomer.yachtapp.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.oldzoomer.yachtapp.data.Observation
import ru.oldzoomer.yachtapp.data.ThreatLevel
import ru.oldzoomer.yachtapp.data.WeatherAssessment

class OfflineSkyAnalyzer(private val context: Context) {

    private val weatherClassifier = WeatherClassifier(context)

    /**
     * Weather labels that indicate storm conditions.
     */
    private val stormLabels = setOf("storm", "rainy")

    /**
     * Weather labels that indicate cloud presence.
     */
    private val cloudLabels = setOf("cloudy", "overcast", "foggy", "hazy")

    /**
     * Weather labels that indicate clear/fair weather.
     */
    private val clearLabels = setOf("sunny", "clear")

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
                val weatherLabel: String?,
                val stormSignal: Boolean,
                val cloudSignal: Boolean,
                val clearSignal: Boolean,
                val skyCoverageEstimate: Int // 0-8 octas from this single frame
            )

            val frameAnalyses = mutableListOf<FrameAnalysis>()
            var maxCloudiness = 0f
            var totalDarkPixels = 0
            var totalSampledPixels = 0

            // Global weather labels aggregated across all frames
            val detectedWeatherLabels = mutableMapOf<String, Int>()

            for (obs in observations) {
                try {
                    val bitmap = loadDownsampledBitmapFromUri(obs.photoUri.toUri(), maxDimension = 800)
                    if (bitmap != null) {
                        // 1. Custom Weather Model (TFLite)
                        val weatherLabel = weatherClassifier.classify(bitmap)
                        if (weatherLabel != null) {
                            detectedWeatherLabels[weatherLabel] = detectedWeatherLabels.getOrDefault(weatherLabel, 0) + 1
                        }

                        // 2. Pixel luminance & cloud cover analysis per frame
                        val bitmapAnalysis = analyzeBitmap(bitmap)
                        totalDarkPixels += bitmapAnalysis.darkPixels
                        totalSampledPixels += bitmapAnalysis.totalPixels
                        if (bitmapAnalysis.cloudinessRatio > maxCloudiness) {
                            maxCloudiness = bitmapAnalysis.cloudinessRatio
                        }

                        // 3. Per-frame signal classification
                        val frameStormSignal = weatherLabel in stormLabels
                        val frameCloudSignal = weatherLabel in cloudLabels
                        val frameClearSignal = weatherLabel in clearLabels

                        // Estimate cloudiness octas from this single frame
                        val frameCloudOctas = (bitmapAnalysis.cloudinessRatio * 8).toInt().coerceIn(0, 8)
                        // Boost if ML confirms clouds but pixel brightness doesn't fully show it
                        val adjustedCloudOctas = if (frameCloudSignal && frameCloudOctas < 3) 4 else frameCloudOctas

                        frameAnalyses.add(FrameAnalysis(
                            darkRatio = bitmapAnalysis.darkPixels.toFloat() / maxOf(bitmapAnalysis.totalPixels, 1),
                            cloudinessRatio = bitmapAnalysis.cloudinessRatio,
                            weatherLabel = weatherLabel,
                            stormSignal = frameStormSignal,
                            cloudSignal = frameCloudSignal,
                            clearSignal = frameClearSignal,
                            skyCoverageEstimate = adjustedCloudOctas
                        ))

                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("OfflineSkyAnalyzer", "Error analyzing photo: ${obs.photoUri}", e)
                }
            }

            // Format ML labels summary sorted by frequency
            val mlSummaryList = detectedWeatherLabels.entries
                .sortedByDescending { it.value }
                .take(6)
                .map { "${it.key} (${it.value} ракурс)" }

            // ==========================================
            // CONSENSUS-BASED DECISION ENGINE
            // ==========================================

            // Count how many frames signal each condition
            val stormFrameCount = frameAnalyses.count { it.stormSignal }
            val cloudFrameCount = frameAnalyses.count { it.cloudSignal }
            val clearFrameCount = frameAnalyses.count { it.clearSignal }
            val totalFrames = maxOf(frameAnalyses.size, 1)

            // Global consensus thresholds
            val consensusStorm = stormFrameCount >= 2 || (stormFrameCount >= 1 && hasGusts)
            val consensusClouds = cloudFrameCount >= 2 || cloudFrameCount >= 1 && maxCloudiness > 0.5f
            val dominantClear = clearFrameCount >= totalFrames * 0.6f

            // Average dark ratio across all frames
            val avgDarkRatio = if (frameAnalyses.isNotEmpty()) {
                frameAnalyses.map { it.darkRatio }.average().toFloat()
            } else 0f

            // Evaluate cloudiness in octas (0-8)
            var cloudinessOctas = (maxCloudiness * 8).toInt().coerceIn(0, 8)
            if (consensusClouds && cloudinessOctas < 3) {
                cloudinessOctas = 4
            }
            if (dominantClear && cloudinessOctas > 5) {
                cloudinessOctas = maxOf(cloudinessOctas - 2, 0)
            }

            val darkRatio = if (totalSampledPixels > 0) totalDarkPixels.toFloat() / totalSampledPixels else 0f

            val hazards = mutableListOf<String>()
            val recommendations = mutableListOf<String>()

            var threat = ThreatLevel.CALM
            var squallRisk = 10
            var dominantType = "Кучевые (Cu / Sc)"

            // --- DANGER: Squall detection ---
            if ((darkRatio > 0.35f || avgDarkRatio > 0.25f) && hasGusts && consensusStorm) {
                threat = ThreatLevel.DANGER_SQUALL
                squallRisk = 95
                dominantType = "Штормовые условия — подтверждено несколькими ракурсами"
                hazards.add("ВЫСОКИЙ РИСК ШКВАЛА! Тёмные тучи + порывистый ветер + ML-подтверждение")
                recommendations.add("СРОЧНО: Уберите стаксель, оставьте минимальный штормовой стаксель или грот")
                recommendations.add("Наденьте страховочные пояса и жилеты, закрепитесь в кокпите")
            } else if ((darkRatio > 0.45f) && hasGusts) {
                threat = ThreatLevel.DANGER_SQUALL
                squallRisk = 90
                dominantType = "Тёмные тучи со стороны фронта"
                hazards.add("ВЫСОКИЙ РИСК ШКВАЛА! Тёмные тучи + порывистый ветер")
                recommendations.add("СРОЧНО: Уберите стаксель, оставьте минимальный штормовой стаксель или грот")
                recommendations.add("Наденьте страховочные пояса и жилеты, закрепитесь в кокпите")
            }

            // --- WARNING: Storm signal ---
            else if (consensusStorm) {
                threat = ThreatLevel.WARNING
                squallRisk = 65
                dominantType = "Штормовые признаки"
                hazards.add("ML-модель и датчики обнаружили штормовые условия")
                recommendations.add("Уменьшите площадь парусности (взять рифы)")
                recommendations.add("Убедитесь, что люки и лючки задраены")
            } else if (darkRatio > 0.35f && cloudinessOctas >= 6) {
                threat = ThreatLevel.WARNING
                squallRisk = 60
                dominantType = "Плотная пелена"
                hazards.add("Тёмные тучи занимают более 7/8 неба, давление падает")
                recommendations.add("Уменьшите площадь парусности (взять рифы)")
                recommendations.add("Проверьте страховку и подготовьте штормовой комплект")
            }

            // --- CAUTION: Moderate risk signals ---
            else if (consensusClouds) {
                threat = ThreatLevel.WARNING
                squallRisk = 70
                dominantType = "Накопление облаков"
                hazards.add("ML-модель идентифицировала облачные условия в нескольких ракурсах")
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