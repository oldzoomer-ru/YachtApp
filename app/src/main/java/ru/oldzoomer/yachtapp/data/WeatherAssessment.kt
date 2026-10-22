package ru.oldzoomer.yachtapp.data

enum class ThreatLevel(val label: String, val colorCode: String) {
    CALM("Спокойно", "GREEN"),
    CAUTION("Внимание: перемена погоды", "YELLOW"),
    WARNING("Ухудшение погоды", "ORANGE"),
    DANGER_SQUALL("ОПАСНОСТЬ ШКВАЛА / ШТОРМ", "RED")
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
    val mlDetectedLabels: List<String> = emptyList()
)
