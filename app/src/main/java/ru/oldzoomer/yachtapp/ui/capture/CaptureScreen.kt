package ru.oldzoomer.yachtapp.ui.capture

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ru.oldzoomer.yachtapp.camera.CameraManager
import ru.oldzoomer.yachtapp.camera.CameraPreview
import ru.oldzoomer.yachtapp.data.Direction
import ru.oldzoomer.yachtapp.data.ThreatLevel
import ru.oldzoomer.yachtapp.data.WeatherAssessment

@Composable
fun CaptureScreen(
    onStartObservation: () -> Unit,
    onBackToStart: () -> Unit,
    onRetry: () -> Unit,
    cameraManager: CameraManager,
    viewModel: CaptureViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is CaptureUiState.Idle -> {
                IdleView(onStart = onStartObservation)
            }

            is CaptureUiState.Capturing -> {
                CapturingView(
                    currentStep = state.photoIndex,
                    totalSteps = state.totalPhotos,
                    direction = state.direction,
                    currentAzimuth = state.currentAzimuth,
                    pressureHpa = state.pressureHpa,
                    hasPressure = state.hasPressure,
                    cameraManager = cameraManager,
                    onCaptureClick = { viewModel.captureCurrentStep() }
                )
            }

            is CaptureUiState.ReadyForNext -> {
                ReadyForNextView(
                    state = state,
                    onNextClick = { viewModel.continueToNextCapture() },
                    onCancelClick = { viewModel.cancelObservation() }
                )
            }

            is CaptureUiState.Reviewing -> {
                ReviewView(
                    state = state,
                    onWindChanged = { knots, gusty -> viewModel.updateUserSettings(knots, gusty) },
                    onConfirm = { viewModel.processForecastAndSave() },
                    onBack = { viewModel.cancelObservation() }
                )
            }

            is CaptureUiState.ForecastResult -> {
                ForecastResultView(
                    assessment = state.assessment,
                    onDone = onBackToStart
                )
            }

            is CaptureUiState.Error -> {
                ErrorView(state.message, onRetry = onRetry)
            }

            is CaptureUiState.Loading -> {
                LoadingView()
            }
        }
    }
}

@Composable
fun IdleView(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Explore,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SkyWatcher Offline",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Метеопрогноз по небу и воде без интернета.\n4 ракурса от ветра + 1 на север.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(36.dp))
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Начать съёмку погоды", fontSize = 16.sp)
        }
    }
}

@Composable
fun CapturingView(
    currentStep: Int,
    totalSteps: Int,
    direction: Direction,
    currentAzimuth: Float,
    pressureHpa: Float,
    hasPressure: Boolean,
    cameraManager: CameraManager,
    onCaptureClick: () -> Unit
) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraPreview(
                cameraManager = cameraManager,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Для съёмки погоды необходим доступ к камере",
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        permissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }) {
                        Text("Разрешить доступ к камере")
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(
                        alpha = 0.85f
                    )
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ракурс $currentStep из $totalSteps",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (hasPressure) {
                            Text(
                                text = "P: ${pressureHpa.toInt()} hPa",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Направление: ${direction.displayName}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Азимут телефона: ${currentAzimuth.toInt()}°",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.5f))
                )
            }

            Button(
                onClick = onCaptureClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Сделать снимок (${currentStep}/$totalSteps)", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun ReadyForNextView(
    state: CaptureUiState.ReadyForNext,
    onNextClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(16.dp))
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Снимок сохранен!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Следующий шаг: ${state.nextDirection.displayName} (Ракурс ${state.nextIndex} из 5)",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier
                    .size(220.dp, 160.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            ) {
                AsyncImage(
                    model = state.lastPhotoUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onNextClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Продолжить (Следующий ракурс)")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCancelClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Отменить съёмку")
            }
        }
    }
}

@Composable
fun ReviewView(
    state: CaptureUiState.Reviewing,
    onWindChanged: (Int, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit
) {
    var windKnots by remember { mutableIntStateOf(state.userWindKnots) }
    var isGusty by remember { mutableStateOf(state.userIsGusty) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null
                    )
                }
                Text(
                    text = "Проверка наблюдений (5 ракурсов)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.capturedObservations.forEach { obs ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        ) {
                            AsyncImage(
                                model = obs.photoUri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Text(
                            text = obs.direction.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Уточнение параметров ветра и среды",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Сила ветра: $windKnots узлов (~${(windKnots * 1.852).toInt()} км/ч)")
                    Slider(
                        value = windKnots.toFloat(),
                        onValueChange = {
                            windKnots = it.toInt()
                            onWindChanged(windKnots, isGusty)
                        },
                        valueRange = 0f..45f,
                        steps = 44
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Характер ветра: Порывистый")
                        Switch(
                            checked = isGusty,
                            onCheckedChange = {
                                isGusty = it
                                onWindChanged(windKnots, isGusty)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Барометр: ${state.currentPressure.toInt()} hPa",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Запустить оффлайн-анализ погоды", fontSize = 16.sp)
        }
    }
}

@Composable
fun ForecastResultView(
    assessment: WeatherAssessment,
    onDone: () -> Unit
) {
    val threatColor = when (assessment.threatLevel) {
        ThreatLevel.CALM -> Color(0xFF2E7D32)
        ThreatLevel.CAUTION -> Color(0xFFF57F17)
        ThreatLevel.WARNING -> Color(0xFFE65100)
        ThreatLevel.DANGER_SQUALL -> Color(0xFFC62828)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Text(
                text = "Метеопрогноз SkyWatcher",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = threatColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = assessment.threatLevel.label,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Риск шквала: ${assessment.squallRiskPercent}% • Ветер до ${assessment.estimatedWindKnots} уз.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Облачность: ${assessment.cloudinessOctas}/8 (${assessment.dominantCloudType})")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Давление: ${assessment.pressureHpa.toInt()} hPa (${assessment.pressureTrend})")

                    if (assessment.mlDetectedLabels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "🤖 ML-анализ облачности (ML Kit On-Device):",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            assessment.mlDetectedLabels.joinToString(" • "),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (assessment.hazards.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "⚠️ Факторы риска:",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        assessment.hazards.forEach { hazard ->
                            Text("• $hazard", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "💡 Рекомендации шкиперу:",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    assessment.recommendations.forEach { rec ->
                        Text("• $rec", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Новое наблюдение")
        }
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Повторить")
        }
    }
}

@Composable
fun LoadingView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Анализируем небо и воду оффлайн...")
        }
    }
}
