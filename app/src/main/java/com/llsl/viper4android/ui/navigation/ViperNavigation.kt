package com.llsl.viper4android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.llsl.viper4android.ui.screens.main.MainScreen

@Composable
fun ViperNavigation(
    onStartCapture: () -> Unit = {},
    onStopCapture: () -> Unit = {},
    onStartShizukuCapture: () -> Unit = {},
    onStopShizukuCapture: () -> Unit = {},
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main",
    ) {
        composable("main") {
            MainScreen(
                onStartCapture        = onStartCapture,
                onStopCapture         = onStopCapture,
                onStartShizukuCapture = onStartShizukuCapture,
                onStopShizukuCapture  = onStopShizukuCapture,
            )
        }
    }
}
