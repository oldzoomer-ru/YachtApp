package ru.oldzoomer.yachtapp.data

import android.net.Uri

/**
 * Represents temporary data captured during a weather observation sequence.
 */
data class ObservationData(
    val direction: Direction,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val photoUri: Uri
)
