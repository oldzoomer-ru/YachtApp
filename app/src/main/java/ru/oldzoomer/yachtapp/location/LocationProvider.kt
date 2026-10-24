package ru.oldzoomer.yachtapp.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class LocationProvider(context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? = withTimeoutOrNull(5000L.milliseconds) {
        suspendCancellableCoroutine { continuation ->
            try {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    null
                ).addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }.addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }.addOnCanceledListener {
                    if (continuation.isActive) continuation.cancel()
                }
            } catch (e: SecurityException) {
                Log.e("LocationProvider", "Location permission missing", e)
                if (continuation.isActive) continuation.resume(null)
            } catch (e: Exception) {
                Log.e("LocationProvider", "Failed to get location", e)
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

}
