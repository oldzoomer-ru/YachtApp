package ru.oldzoomer.yachtapp.data

import androidx.room.TypeConverter

enum class Direction(val displayName: String, val angleOffset: Float) {
    WINDWARD("Левентик (В лицо)", 0f),
    STARBOARD("Правый борт", 90f),
    LEEWARD("В корму", 180f),
    PORT("Левый борт", 270f),
    NORTH("Север", 0f); // Absolute north reference if needed

    companion object {
        fun sequenceForCapture(): List<Direction> = listOf(
            WINDWARD,
            STARBOARD,
            LEEWARD,
            PORT,
            NORTH
        )
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
            Direction.WINDWARD
        }
    }
}
