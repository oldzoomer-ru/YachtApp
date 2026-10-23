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

            // --- 1. Barometric Pressure Trend Calculation ---
            val sortedObs = observations.sortedBy { it.timestamp }
            val currentPressure = sortedObs.last().pressureHpa
            val oldestPressure = sortedObs.first().pressureHpa

            val avgWind = observations.map { it.userWindKnots }.average().toInt()
            val hasGusts = observations.any { it.userIsGusty }

            // Difference in hours (minimum 0.5h to prevent division spikes when photos are taken quickly)
            val timeDiffHours = maxOf((sortedObs.last().timestamp - sortedObs.first().timestamp).toFloat() / 3_600_000f, 0.5f)
            val pressureRatePerHour = (currentPressure - oldestPressure) / timeDiffHours

            val isRapidPressureDrop = pressureRatePerHour < -1.5f // Drop faster than 1.5 hPa/hr is alarming
            val isModeratePressureDrop = pressureRatePerHour < -0.5f
            val isRapidPressureRise = pressureRatePerHour > 1.5f

            val pressureTrendStr = when {
                isRapidPressureDrop -> "Резкое падение (к шторму)"
                isModeratePressureDrop -> "Падает"
                isRapidPressureRise -> "Резкий рост"
                pressureRatePerHour > 0.5f -> "Растет"
                else -> "Стабильно"
            }

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

            var highConfidenceStormSignal = false

            for (obs in observations) {
                try {
                    val bitmap = loadDownsampledBitmapFromUri(obs.photoUri.toUri(), maxDimension = 800)
                    if (bitmap != null) {
                        // 1. Custom Weather Model (TFLite) with confidence filtering
                        val weatherResult = weatherClassifier.classify(bitmap, minConfidence = 0.45f)
                        val weatherLabel = weatherResult?.label

                        if (weatherResult != null) {
                            detectedWeatherLabels[weatherResult.label] = detectedWeatherLabels.getOrDefault(weatherResult.label, 0) + 1

                            // High confidence storm signal (> 75%)
                            if (weatherResult.label in stormLabels && weatherResult.confidence > 0.75f) {
                                highConfidenceStormSignal = true
                            }
                        }

                        // 2. Pixel analysis via Red/Blue ratio & dark storm cloud detection
                        val bitmapAnalysis = analyzeSkyPixels(bitmap)
                        totalDarkPixels += bitmapAnalysis.darkPixels
                        totalSampledPixels += bitmapAnalysis.totalPixels
                        if (bitmapAnalysis.cloudinessRatio > maxCloudiness) {
                            maxCloudiness = bitmapAnalysis.cloudinessRatio
                        }

                        // 3. Per-frame signal classification
                        val frameStormSignal = weatherLabel != null && weatherLabel in stormLabels
                        val frameCloudSignal = weatherLabel != null && weatherLabel in cloudLabels
                        val frameClearSignal = weatherLabel != null && weatherLabel in clearLabels

                        // Estimate cloudiness octas from this single frame
                        val frameCloudOctas = (bitmapAnalysis.cloudinessRatio * 8).toInt().coerceIn(0, 8)
                        // Boost if ML confirms clouds but pixel ratio doesn't fully show it
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
            // CONSENSUS-BASED DECISION ENGINE & THREAT MATRIX
            // ==========================================

            // Count how many frames signal each condition
            val stormFrameCount = frameAnalyses.count { it.stormSignal }
            val cloudFrameCount = frameAnalyses.count { it.cloudSignal }
            val clearFrameCount = frameAnalyses.count { it.clearSignal }
            val totalFrames = maxOf(frameAnalyses.size, 1)

            // Global consensus thresholds
            val consensusStorm = stormFrameCount >= 2 || (stormFrameCount >= 1 && (hasGusts || highConfidenceStormSignal)) || (highConfidenceStormSignal && hasGusts)
            val consensusClouds = cloudFrameCount >= 2 || (cloudFrameCount >= 1 && maxCloudiness > 0.5f)
            val dominantClear = clearFrameCount >= totalFrames * 0.6f

            // Average dark ratio across all frames
            val avgDarkRatio = if (frameAnalyses.isNotEmpty()) {
                frameAnalyses.map { it.darkRatio }.average().toFloat()
            } else 0f

            val overallDarkRatio = if (totalSampledPixels > 0) totalDarkPixels.toFloat() / totalSampledPixels else 0f
            val darkRatio = maxOf(avgDarkRatio, overallDarkRatio)

            // Evaluate cloudiness in octas (0-8)
            var cloudinessOctas = (maxCloudiness * 8).toInt().coerceIn(0, 8)
            if (consensusClouds && cloudinessOctas < 3) {
                cloudinessOctas = 4
            }
            if (dominantClear && cloudinessOctas > 5) {
                cloudinessOctas = maxOf(cloudinessOctas - 2, 0)
            }

            val hazards = mutableListOf<String>()
            val recommendations = mutableListOf<String>()

            var threat = ThreatLevel.CALM
            var squallRisk = 10
            var dominantType = "Кучевые / Слоистые облака"

            // --- 1. DANGER: Squall / Storm Conditions ---
            if ((consensusStorm && hasGusts && (darkRatio > 0.2f || isRapidPressureDrop)) ||
                (darkRatio > 0.35f && hasGusts && (isRapidPressureDrop || avgWind > 25))
            ) {
                threat = ThreatLevel.DANGER_SQUALL
                squallRisk = 95
                dominantType = "Штормовые / предшквальные кучево-дождевые тучи (Cb)"
                hazards.add("ВЫСОКИЙ РИСК ШКВАЛА! Тёмные тучи + порывистый ветер / падение давления")
                recommendations.add("СРОЧНО: Уберите стаксель, оставьте минимальный штормовой стаксель или грот")
                recommendations.add("Наденьте страховочные пояса и жилеты, закрепитесь в кокпите")
            }

            // --- 2. WARNING: Rapid pressure drop, storm signs, or severe cloudiness with wind ---
            else if (isRapidPressureDrop) {
                threat = ThreatLevel.WARNING
                squallRisk = 60
                dominantType = "Приближение атмосферного фронта"
                hazards.add("Резкое падение барометрического давления (предупреждение о приближении фронта)")
                recommendations.add("Уменьшите площадь парусности (взять рифы)")
                recommendations.add("Задрайте люки, проверьте страховки и подготовьте штормовой комплект")
            } else if (consensusStorm) {
                threat = ThreatLevel.WARNING
                squallRisk = 65
                dominantType = "Штормовые признаки"
                hazards.add("ML-модель и датчики обнаружили штормовые условия")
                recommendations.add("Уменьшите площадь парусности (взять рифы)")
                recommendations.add("Убедитесь, что люки и лючки задраены")
            } else if (hasGusts && avgWind > 22 && cloudinessOctas >= 6) {
                threat = ThreatLevel.WARNING
                squallRisk = 70
                dominantType = "Плотная облачность с сильным ветром"
                hazards.add("Сильный порывистый ветер ($avgWind уз.) и плотная облачность ($cloudinessOctas/8)")
                recommendations.add("Уменьшите площадь парусности (взять рифы)")
                recommendations.add("Проверьте страховку и подготовьте штормовой комплект")
            } else if (darkRatio > 0.35f && cloudinessOctas >= 6) {
                threat = ThreatLevel.WARNING
                squallRisk = 60
                dominantType = "Плотная темная пелена"
                hazards.add("Тёмная облачность занимает более 6/8 неба")
                recommendations.add("Уменьшите площадь парусности (взять рифы)")
                recommendations.add("Проверьте страховку и подготовьте штормовой комплект")
            }

            // --- 3. CAUTION: Convective accumulation or moderate wind / gusts ---
            else if (consensusClouds) {
                threat = ThreatLevel.CAUTION
                squallRisk = 25
                dominantType = "Слоистая / Кучевая облачность (без признаков конвекции)"
                hazards.add("Фиксируется облачность. Шквалистая угроза не выявлена")
                recommendations.add("Следите за изменениями формы облаков и направлением ветра")
            } else if (hasGusts || avgWind > 20) {
                threat = ThreatLevel.CAUTION
                squallRisk = 50
                dominantType = "Порывистый / Сильный ветер"
                hazards.add("Отмечен порывистый ветер или высокая базовая скорость ($avgWind уз.)")
                recommendations.add("Внимательно следите за поверхностью воды на предмет ряби и «барашков»")
            }

            // --- 4. CALM: Fair / Clear weather ---
            if (dominantClear && cloudinessOctas <= 2 && !hasGusts && !consensusStorm && !isRapidPressureDrop) {
                threat = ThreatLevel.CALM
                squallRisk = 5
                dominantType = "Малооблачно / Ясно"
                recommendations.clear()
                recommendations.add("Отличные условия для плавания. Стабильная погода.")
            } else if (threat == ThreatLevel.CALM && cloudinessOctas <= 3) {
                threat = ThreatLevel.CAUTION
                squallRisk = 20
                dominantType = "Малооблачно / Переменная облачность"
                recommendations.add("Переменная облачность. Возможны локальные заходы ветра.")
            }

            WeatherAssessment(
                timestamp = System.currentTimeMillis(),
                threatLevel = threat,
                cloudinessOctas = cloudinessOctas,
                dominantCloudType = dominantType,
                pressureHpa = currentPressure,
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

    /**
     * Downsamples sky portion of bitmap and evaluates cloudiness via Red/Blue ratio (Mie scattering vs Rayleigh scattering)
     * and dark storm cloud ratio (dense dark storm cloud pixels).
     */
    private fun analyzeSkyPixels(bitmap: Bitmap): BitmapAnalysis {
        val sampleW = 32
        val sampleH = 20

        // Take upper 65% of image (sky region)
        val skyHeight = (bitmap.height * 0.65f).toInt().coerceAtLeast(1)
        val skyRegion = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, skyHeight)
        val scaled = Bitmap.createScaledBitmap(skyRegion, sampleW, sampleH, true)
        if (skyRegion != bitmap) {
            skyRegion.recycle()
        }

        var darkStormPixels = 0
        var cloudPixels = 0
        val total = sampleW * sampleH

        for (y in 0 until sampleH) {
            for (x in 0 until sampleW) {
                val p = scaled.getPixel(x, y)
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF

                val brightness = r.toFloat() * 0.299f + g.toFloat() * 0.587f + b.toFloat() * 0.114f
                val rbRatio = if (b > 0) r.toFloat() / b.toFloat() else 1.0f

                // Clear sky: blue dominates (rbRatio < 0.75)
                // Cloudiness: neutral spectrum (rbRatio >= 0.75)
                if (rbRatio >= 0.75f) {
                    cloudPixels++
                    // Heavy dark squall cloud (brightness < 70f)
                    if (brightness < 70f) {
                        darkStormPixels++
                    }
                }
            }
        }
        scaled.recycle()

        return BitmapAnalysis(
            darkPixels = darkStormPixels,
            totalPixels = total,
            cloudinessRatio = cloudPixels.toFloat() / total
        )
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