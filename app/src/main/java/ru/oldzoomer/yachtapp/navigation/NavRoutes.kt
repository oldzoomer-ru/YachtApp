package ru.oldzoomer.yachtapp.navigation

import kotlinx.serialization.Serializable

sealed interface NavRoutes {

    @Serializable
    object Capture : NavRoutes

}
