package ru.oldzoomer.yachtapp

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.oldzoomer.yachtapp.camera.CameraManager
import ru.oldzoomer.yachtapp.data.AppDatabase
import ru.oldzoomer.yachtapp.data.WeatherRepositoryImpl
import ru.oldzoomer.yachtapp.location.LocationProvider
import ru.oldzoomer.yachtapp.location.PermissionManager
import ru.oldzoomer.yachtapp.navigation.NavRoutes
import ru.oldzoomer.yachtapp.ui.capture.CaptureScreen
import ru.oldzoomer.yachtapp.ui.capture.CaptureViewModel
import ru.oldzoomer.yachtapp.ui.theme.YachtAppTheme

class MainActivity : ComponentActivity() {

    private lateinit var requestPermissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ -> }

        requestAppPermissions()

        setContent {
            YachtAppTheme {
                MainNavigation()
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = PermissionManager.REQUIRED_PERMISSIONS
        val list = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED
        }
        if (list.isNotEmpty()) {
            requestPermissionLauncher.launch(list.toTypedArray())
        }
    }

    @Composable
    private fun MainNavigation() {
        val navController = rememberNavController()
        val context = LocalContext.current
        val app = context.applicationContext as android.app.Application

        val database = remember { AppDatabase.getDatabase(app) }
        val repository = remember { WeatherRepositoryImpl(database.observationDao()) }
        val locationProvider = remember { LocationProvider(app) }
        val cameraManager = remember { CameraManager(app) }

        val viewModel: CaptureViewModel = viewModel(
            factory = CaptureViewModelFactory(app, cameraManager, locationProvider, repository)
        )

        Scaffold(
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                NavHost(
                    navController = navController,
                    startDestination = NavRoutes.Capture
                ) {
                    composable<NavRoutes.Capture> {
                        CaptureScreen(
                            onStartObservation = { viewModel.startNewObservation() },
                            onBackToStart = { viewModel.cancelObservation() },
                            onRetry = { viewModel.cancelObservation() },
                            cameraManager = cameraManager,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

class CaptureViewModelFactory(
    private val application: android.app.Application,
    private val cameraManager: CameraManager,
    private val locationProvider: LocationProvider,
    private val repository: ru.oldzoomer.yachtapp.data.WeatherRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CaptureViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CaptureViewModel(application, cameraManager, locationProvider, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
