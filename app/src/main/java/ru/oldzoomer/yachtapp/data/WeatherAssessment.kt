package ru.oldzoomer.yachtapp.data

enum class ThreatLevel(val label: String) {
    CALM("Спокойно"),
    CAUTION("Внимание: перемена погоды"),
    WARNING("Ухудшение погоды"),
    DANGER_SQUALL("ОПАСНОСТЬ ШКВАЛА / ШТОРМ")
}

data class WeatherAssessment(
    val timestamp: Long,
    val threatLevel: ThreatLevel,
    val cloudinessOctas: Int, // 0 to 8
    val dominantCloudType: String,
    val pressureHpa: Float,
    val pressureTrend: String, // "Стабильно", "Падает (к шторму)", "Растет"
    val squallRiskPercent: Int,
    val estimatedWindKnots: Int,
    val hazards: List<String>,
    val recommendations: List<String>,
    val mlDetectedLabels: List<String> = emptyList(),
    val forecastConfidence: Float = 0f // 0-1, confidence score for forecast reliability
)
