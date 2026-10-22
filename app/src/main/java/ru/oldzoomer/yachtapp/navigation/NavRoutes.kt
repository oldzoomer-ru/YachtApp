package ru.oldzoomer.yachtapp.navigation

import kotlinx.serialization.Serializable

sealed interface NavRoutes {
    @Serializable
    object Idle : NavRoutes

    @Serializable
    object Capture : NavRoutes

    @Serializable
    data class Review(
        val photoUris: List<String>
    ) : NavRoutes

    @Serializable
    object Success : NavRoutes

    @Serializable
    data class Error(
        val message: String
    ) : NavRoutes
}
