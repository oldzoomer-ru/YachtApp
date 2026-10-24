package ru.oldzoomer.yachtapp.location

import android.Manifest

class PermissionManager {

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

}
