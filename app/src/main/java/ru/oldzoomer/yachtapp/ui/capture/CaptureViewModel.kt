package ru.oldzoomer.yachtapp.ui.capture

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.oldzoomer.yachtapp.analysis.OfflineSkyAnalyzer
import ru.oldzoomer.yachtapp.camera.CameraManager
import ru.oldzoomer.yachtapp.data.Direction
import ru.oldzoomer.yachtapp.data.DirectionMode
import ru.oldzoomer.yachtapp.data.Observation
import ru.oldzoomer.yachtapp.data.WeatherRepository
import ru.oldzoomer.yachtapp.location.LocationProvider
import ru.oldzoomer.yachtapp.sensor.SensorDataManager

class CaptureViewModel(
    application: Application,
    private val cameraManager: CameraManager,
    private val locationProvider: LocationProvider,
    private val repository: WeatherRepository,
) : AndroidViewModel(application) {

    private val sensorDataManager = SensorDataManager(application)
    private val skyAnalyzer = OfflineSkyAnalyzer(application)

    override fun onCleared() {
        // Release resources to prevent memory leaks
        cameraManager.release()
    }

    private val _uiState = MutableStateFlow<CaptureUiState>(CaptureUiState.Idle)
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private var selectedDirectionMode = DirectionMode.DIAGONAL

    private val captureSequence: List<Direction>
        get() = Direction.sequenceForMode(selectedDirectionMode)

    private val capturedList = mutableListOf<CapturedObservation>()
    private var currentIndex = 0
    private var currentAzimuth = 0f
    private var currentPressure = 1013.25f
    private var hasPressure = false

    private var userWindKnots = 12
    private var userIsGusty = false

    init {
        viewModelScope.launch {
            sensorDataManager.sensorReadings.collect { reading ->
                _uiState.value.let { state ->
                    if (state is CaptureUiState.Capturing) {
                        _uiState.value = state.copy(
                            currentAzimuth = reading.azimuthDegrees,
                            pressureHpa = reading.pressureHpa,
                            hasPressure = reading.hasPressureSensor,
                        )
                    }
                }
            }
        }
    }

    fun setDirectionMode(mode: DirectionMode) {
        selectedDirectionMode = mode
    }

    fun getDirectionMode(): DirectionMode = selectedDirectionMode

    fun startNewObservation() {
        capturedList.clear()
        currentIndex = 0
        val sequence = captureSequence
        viewModelScope.launch {
            _uiState.value = CaptureUiState.Capturing(
                photoIndex = 1,
                totalPhotos = sequence.size,
                direction = sequence[0],
                directionMode = selectedDirectionMode,
                currentAzimuth = currentAzimuth,
                pressureHpa = currentPressure,
                hasPressure = hasPressure
            )
        }
    }

    fun captureCurrentStep() {
        val currentState = _uiState.value
        if ((currentState !is CaptureUiState.Capturing) && (currentState !is CaptureUiState.ReadyForNext)) return

        viewModelScope.launch {
            try {
                val photoUri = withContext(Dispatchers.IO) {
                    cameraManager.takePhoto().first().getOrThrow()
                }

                val sequence = captureSequence
                val currentDirection = sequence[currentIndex]
                capturedList.add(CapturedObservation(currentDirection, photoUri))

                currentIndex++
                if (currentIndex < sequence.size) {
                    val nextDir = sequence[currentIndex]
                    _uiState.value = CaptureUiState.ReadyForNext(
                        capturedObservations = capturedList.toList(),
                        nextDirection = nextDir,
                        nextIndex = currentIndex + 1,
                        lastPhotoUri = photoUri
                    )
                } else {
                    // All 5 photos captured, go to review
                    _uiState.value = CaptureUiState.Reviewing(
                        capturedObservations = capturedList.toList(),
                        currentPressure = currentPressure,
                        userWindKnots = userWindKnots,
                        userIsGusty = userIsGusty
                    )
                }
            } catch (e: Exception) {
                _uiState.value = CaptureUiState.Error("Ошибка съёмки: ${e.localizedMessage}")
            }
        }
    }

    fun continueToNextCapture() {
        val sequence = captureSequence
        if (currentIndex < sequence.size) {
            _uiState.value = CaptureUiState.Capturing(
                photoIndex = currentIndex + 1,
                totalPhotos = sequence.size,
                direction = sequence[currentIndex],
                directionMode = selectedDirectionMode,
                currentAzimuth = currentAzimuth,
                pressureHpa = currentPressure,
                hasPressure = hasPressure
            )
        }
    }

    fun updateUserSettings(windKnots: Int, isGusty: Boolean) {
        userWindKnots = windKnots
        userIsGusty = isGusty
        val state = _uiState.value
        if (state is CaptureUiState.Reviewing) {
            _uiState.value = state.copy(
                userWindKnots = windKnots,
                userIsGusty = isGusty
            )
        }
    }

    fun processForecastAndSave() {
        viewModelScope.launch {
            _uiState.value = CaptureUiState.Loading
            try {
                val location = withContext(Dispatchers.IO) {
                    locationProvider.getCurrentLocation()
                }
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0
                val timestamp = System.currentTimeMillis()

                val observationsToSave = capturedList.map {
                    Observation(
                        timestamp = timestamp,
                        latitude = lat,
                        longitude = lon,
                        photoUri = it.photoUri.toString(),
                        direction = it.direction,
                        pressureHpa = currentPressure,
                        userWindKnots = userWindKnots,
                        userIsGusty = userIsGusty
                    )
                }

                withContext(Dispatchers.IO) {
                    repository.clearAllObservations()
                    for (obs in observationsToSave) {
                        repository.insertObservation(obs)
                    }
                }

                val assessment = withContext(Dispatchers.IO) {
                    skyAnalyzer.analyzeObservations(observationsToSave)
                }

                _uiState.value = CaptureUiState.ForecastResult(assessment)
            } catch (e: Exception) {
                _uiState.value = CaptureUiState.Error("Ошибка анализа: ${e.localizedMessage}")
            }
        }
    }

    fun cancelObservation() {
        capturedList.clear()
        currentIndex = 0
        _uiState.value = CaptureUiState.Idle
    }
}
