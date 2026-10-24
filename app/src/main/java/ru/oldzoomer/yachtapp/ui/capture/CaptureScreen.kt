package ru.oldzoomer.yachtapp.ui.capture

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import ru.oldzoomer.yachtapp.data.DirectionMode
import ru.oldzoomer.yachtapp.data.ThreatLevel
import ru.oldzoomer.yachtapp.data.WeatherAssessment
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CaptureScreen(
    onStartObservation: () -> Unit,
    onBackToStart: () -> Unit,
    onRetry: () -> Unit,
    cameraManager: CameraManager,
    viewModel: CaptureViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedMode by remember { mutableStateOf(viewModel.getDirectionMode()) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is CaptureUiState.Idle -> {
                IdleView(
                    selectedMode = selectedMode,
                    onModeSelected = { mode ->
                        selectedMode = mode
                        viewModel.setDirectionMode(mode)
                    },
                    onStart = {
                        viewModel.setDirectionMode(selectedMode)
                        onStartObservation()
                    },
                )
            }

            is CaptureUiState.Capturing -> {
                CapturingView(
                    currentStep = state.photoIndex,
                    totalSteps = state.totalPhotos,
                    direction = state.direction,
                    directionMode = state.directionMode,
                    currentAzimuth = state.currentAzimuth,
                    pressureHpa = state.pressureHpa,
                    hasPressure = state.hasPressure,
                    cameraManager = cameraManager,
                    onCaptureClick = { viewModel.captureCurrentStep() },
                )
            }

            is CaptureUiState.ReadyForNext -> {
                ReadyForNextView(
                    state = state,
                    onNextClick = { viewModel.continueToNextCapture() },
                    onCancelClick = { viewModel.cancelObservation() },
                )
            }

            is CaptureUiState.Reviewing -> {
                ReviewView(
                    state = state,
                    onWindChanged = { knots, gusty -> viewModel.updateUserSettings(knots, gusty) },
                    onConfirm = { viewModel.processForecastAndSave() },
                    onBack = { viewModel.cancelObservation() },
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
fun IdleView(
    selectedMode: DirectionMode,
    onModeSelected: (DirectionMode) -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Icon(
                imageVector = Icons.Default.Explore,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "SkyWatcher Offline",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Метеопрогноз по небу и воде без интернета.\n4 ракурса относительно судна + 1 в ветер.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Схема съёмки 4-х ракурсов:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            DirectionModeOptionCard(
                mode = DirectionMode.DIAGONAL,
                isSelected = selectedMode == DirectionMode.DIAGONAL,
                badgeText = "РЕКОМЕНДУЕТСЯ",
                subtitle = "Бейдвинд п/г • Бакштаг п/г • Бакштаг л/г • Бейдвинд л/г",
                onClick = { onModeSelected(DirectionMode.DIAGONAL) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            DirectionModeOptionCard(
                mode = DirectionMode.ORTHOGONAL,
                isSelected = selectedMode == DirectionMode.ORTHOGONAL,
                badgeText = "ПО ОСЯМ СУДНА",
                subtitle = "Левентик • Галфвинд п/г • Фордевинд • Галфвинд л/г",
                onClick = { onModeSelected(DirectionMode.ORTHOGONAL) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "5-й снимок всегда делается в реальный левентик (лицом строго к ветру)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

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
private fun DirectionModeOptionCard(
    mode: DirectionMode,
    isSelected: Boolean,
    badgeText: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mode.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun CapturingView(
    currentStep: Int,
    totalSteps: Int,
    direction: Direction,
    directionMode: DirectionMode,
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
                    Button(
                        onClick = {
                            permissionLauncher.launch(android.Manifest.permission.CAMERA)
                        },
                    ) {
                        Text("Разрешить доступ к камере")
                    }
                }
            }
        }

        // --- CAMERA OVERLAYS ---

        // 1. Horizon Line Overlay with text "ЛИНИЯ ГОРИЗОНТА"
        HorizonGuideOverlay(
            modifier = Modifier.align(Alignment.Center)
        )

        // 2. Top-down Vessel Schematic Diagram showing current angle
        VesselDiagramOverlay(
            direction = direction,
            directionMode = directionMode,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 80.dp, end = 16.dp)
        )

        // 3. UI Controls and Info Cards
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(
                        alpha = 0.88f
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
                        text = direction.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Курс/Ракурс: ${direction.nauticalTerm}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Компас: ${currentAzimuth.toInt()}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onCaptureClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Сделать снимок ($currentStep/$totalSteps)", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun HorizonGuideOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
        ) {
            val width = size.width
            val cy = size.height / 2f
            val strokeWidthPx = 2.dp.toPx()
            val accentColor = Color.White.copy(alpha = 0.9f)
            val glowColor = Color(0x7700E5FF) // Cyan glow for clear visibility against sky/water

            // Outer line glow
            drawLine(
                color = glowColor,
                start = Offset(x = width * 0.05f, y = cy),
                end = Offset(x = width * 0.95f, y = cy),
                strokeWidth = strokeWidthPx * 2.5f
            )

            // Left segment
            drawLine(
                color = accentColor,
                start = Offset(x = width * 0.05f, y = cy),
                end = Offset(x = width * 0.28f, y = cy),
                strokeWidth = strokeWidthPx
            )

            // Left reticle tick
            drawLine(
                color = accentColor,
                start = Offset(x = width * 0.05f, y = cy - 10.dp.toPx()),
                end = Offset(x = width * 0.05f, y = cy + 10.dp.toPx()),
                strokeWidth = strokeWidthPx
            )

            // Right segment
            drawLine(
                color = accentColor,
                start = Offset(x = width * 0.72f, y = cy),
                end = Offset(x = width * 0.95f, y = cy),
                strokeWidth = strokeWidthPx
            )

            // Right reticle tick
            drawLine(
                color = accentColor,
                start = Offset(x = width * 0.95f, y = cy - 10.dp.toPx()),
                end = Offset(x = width * 0.95f, y = cy + 10.dp.toPx()),
                strokeWidth = strokeWidthPx
            )
        }

        // Center pill badge clearly labeling "ЛИНИЯ ГОРИЗОНТА"
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.75f)),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFFFFB300), CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ЛИНИЯ ГОРИЗОНТА",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    ),
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun VesselDiagramOverlay(
    direction: Direction,
    directionMode: DirectionMode,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
        modifier = modifier.size(105.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(85.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f

                // Draw boat hull outline top-down
                val hullPath = Path().apply {
                    val bw = 20.dp.toPx()
                    val bh = 50.dp.toPx()
                    val topY = cy - (bh / 2f)
                    val bottomY = cy + (bh / 2f)

                    // Pointed bow at top
                    moveTo(cx, topY)
                    cubicTo(cx + bw, (topY + bh * 0.3f), cx + bw, (bottomY - bh * 0.1f), cx + bw * 0.7f, bottomY)
                    lineTo(cx - bw * 0.7f, bottomY)
                    cubicTo(cx - bw, bottomY - bh * 0.1f, cx - bw, topY + bh * 0.3f, cx, topY)
                    close()
                }

                drawPath(
                    path = hullPath,
                    color = Color.White.copy(alpha = 0.4f),
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Mast dot
                drawCircle(
                    color = Color.White.copy(alpha = 0.7f),
                    radius = 3.dp.toPx(),
                    center = Offset(cx, cy - 6.dp.toPx())
                )

                val isDiagonal = directionMode == DirectionMode.DIAGONAL

                // Draw direction arrow relative to boat center
                fun drawArrow(
                    angleDegrees: Float,
                    isActive: Boolean
                ) {
                    val arrowLength = 26.dp.toPx()
                    val rad = Math.toRadians((angleDegrees - 90).toDouble()).toFloat()
                    val startDist = 10.dp.toPx()

                    val startX = cx + cos(rad) * startDist
                    val startY = cy + sin(rad) * startDist
                    val endX = cx + cos(rad) * arrowLength
                    val endY = cy + sin(rad) * arrowLength

                    val arrowColor = if (isActive) Color(0xFFFF9800) else Color.White.copy(alpha = 0.25f)
                    val strokeW = if (isActive) 3.5.dp.toPx() else 1.5.dp.toPx()

                    // Line
                    drawLine(
                        color = arrowColor,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = strokeW
                    )

                    // Arrowhead
                    val headLen = 7.dp.toPx()
                    val headAngle = Math.toRadians(25.0).toFloat()
                    val leftHeadX = endX - headLen * cos(rad - headAngle)
                    val leftHeadY = endY - headLen * sin(rad - headAngle)
                    val rightHeadX = endX - headLen * cos(rad + headAngle)
                    val rightHeadY = endY - headLen * sin(rad + headAngle)

                    drawLine(color = arrowColor, start = Offset(endX, endY), end = Offset(leftHeadX, leftHeadY), strokeWidth = strokeW)
                    drawLine(color = arrowColor, start = Offset(endX, endY), end = Offset(rightHeadX, rightHeadY), strokeWidth = strokeW)
                }

                if (direction == Direction.TRUE_WINDWARD) {
                    // Step 5: Highlight wind arrow pointing UP (toward bow, against wind direction)
                    // 180° = arrow points down (toward wind source, which is what we want for "into the wind")
                    drawArrow(180f, isActive = true)
                } else if (isDiagonal) {
                    // Option B: 45°, 135°, 225°, 315°
                    drawArrow(45f, isActive = direction == Direction.CLOSE_HAULED_STBD)
                    drawArrow(135f, isActive = direction == Direction.BROAD_REACH_STBD)
                    drawArrow(225f, isActive = direction == Direction.BROAD_REACH_PORT)
                    drawArrow(315f, isActive = direction == Direction.CLOSE_HAULED_PORT)
                } else {
                    // Option A: 0°, 90°, 180°, 270°
                    drawArrow(0f, isActive = direction == Direction.BOW_WINDWARD)
                    drawArrow(90f, isActive = direction == Direction.BEAM_STARBOARD)
                    drawArrow(180f, isActive = direction == Direction.STERN_LEEWARD)
                    drawArrow(270f, isActive = direction == Direction.BEAM_PORT)
                }
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
                text = "Следующий шаг: ${state.nextDirection.displayName}\n(${state.nextDirection.nauticalTerm}) • Ракурс ${state.nextIndex} из 5",
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
                    .height(95.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = obs.direction.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center
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
