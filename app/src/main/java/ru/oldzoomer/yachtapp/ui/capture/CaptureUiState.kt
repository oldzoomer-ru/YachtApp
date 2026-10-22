package ru.oldzoomer.yachtapp.ui.capture

import android.net.Uri
import ru.oldzoomer.yachtapp.data.Direction
import ru.oldzoomer.yachtapp.data.WeatherAssessment

data class CapturedObservation(
    val direction: Direction,
    val photoUri: Uri
)

sealed interface CaptureUiState {
    data object Idle : CaptureUiState
    data object Loading : CaptureUiState
    data class Capturing(
        val photoIndex: Int, // 1 to 5
        val totalPhotos: Int = 5,
        val direction: Direction,
        val currentAzimuth: Float = 0f,
        val pressureHpa: Float = 1013.25f,
        val hasPressure: Boolean = false
    ) : CaptureUiState

    data class ReadyForNext(
        val capturedObservations: List<CapturedObservation>,
        val nextDirection: Direction,
        val nextIndex: Int,
        val lastPhotoUri: Uri
    ) : CaptureUiState

    data class Reviewing(
        val capturedObservations: List<CapturedObservation>,
        val currentPressure: Float,
        val userWindKnots: Int,
        val userIsGusty: Boolean
    ) : CaptureUiState

    data class ForecastResult(
        val assessment: WeatherAssessment
    ) : CaptureUiState

    data class Error(val message: String) : CaptureUiState
}
