package ru.oldzoomer.yachtapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "observations")
data class Observation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val photoUri: String,
    val direction: Direction,
    val pressureHpa: Float = 1013.25f,
    val userWindKnots: Int = 10,
    val userIsGusty: Boolean = false
)
