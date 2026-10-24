package ru.oldzoomer.yachtapp.data

import androidx.room.TypeConverter

enum class DirectionMode(
    val title: String,
    val description: String,
) {
    DIAGONAL(
        title = "Наискосок (без мачты)",
        description = "Бейдвинд / бакштаг — мачта и ванты не закрывают ракурс"
    ),
    ORTHOGONAL(
        title = "По осям судна",
        description = "Левентик / галфвинд / фордевинд — по бортам, носу и корме"
    )
}

enum class Direction(
    val displayName: String,
    val nauticalTerm: String
) {
    // --- Вариант 1: Наискосок (Диагональная схема - 45°, 135°, 225°, 315°) ---
    CLOSE_HAULED_STBD("Бейдвинд пр. галса", "Нос 45° справа"),
    BROAD_REACH_STBD("Бакштаг пр. галса", "Корма 135° справа"),
    BROAD_REACH_PORT("Бакштаг лев. галса", "Корма 225° слева"),
    CLOSE_HAULED_PORT("Бейдвинд лев. галса", "Нос 315° слева"),

    // --- Вариант 2: По осям судна (Ортогональная схема - 0°, 90°, 180°, 270°) ---
    BOW_WINDWARD("Левентик судна", "Нос судна (0°)"),
    BEAM_STARBOARD("Галфвинд пр. галса", "Правый борт (90°)"),
    STERN_LEEWARD("Фордевинд", "Корма судна (180°)"),
    BEAM_PORT("Галфвинд лев. галса", "Левый борт (270°)"),

    // --- 5-й ракурс: Реальный ветер ---
    TRUE_WINDWARD("Реальный левентик", "Ветер в лицо"),

    // --- Совместимость со старыми записями БД ---
    WINDWARD("Левентик (В лицо)", "0°"),
    STARBOARD("Правый борт", "90°"),
    LEEWARD("В корму", "180°"),
    PORT("Левый борт", "270°"),
    NORTH("Север", "0°");

    companion object {
        fun sequenceForMode(mode: DirectionMode): List<Direction> = when (mode) {
            DirectionMode.DIAGONAL -> listOf(
                CLOSE_HAULED_STBD,
                BROAD_REACH_STBD,
                BROAD_REACH_PORT,
                CLOSE_HAULED_PORT,
                TRUE_WINDWARD
            )
            DirectionMode.ORTHOGONAL -> listOf(
                BOW_WINDWARD,
                BEAM_STARBOARD,
                STERN_LEEWARD,
                BEAM_PORT,
                TRUE_WINDWARD
            )
        }

        fun sequenceForCapture(): List<Direction> = sequenceForMode(DirectionMode.DIAGONAL)
    }
}

class DirectionConverter {
    @TypeConverter
    fun fromDirection(direction: Direction): String {
        return direction.name
    }

    @TypeConverter
    fun toDirection(value: String): Direction {
        return try {
            Direction.valueOf(value)
        } catch (_: Exception) {
            Direction.TRUE_WINDWARD
        }
    }
}
