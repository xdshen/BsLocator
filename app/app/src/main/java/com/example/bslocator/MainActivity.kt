package com.example.bslocator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.bslocator.ui.screens.EstimateScreen
import com.example.bslocator.ui.screens.LogsScreen
import com.example.bslocator.ui.screens.MapScreen
import com.example.bslocator.ui.screens.MeasureScreen
import com.example.bslocator.ui.theme.BsLocatorTheme
import com.example.bslocator.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            android.widget.Toast.makeText(
                this,
                "需要授予以下权限才能正常使用: ${denied.joinToString(", ")}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()

        setContent {
            BsLocatorTheme {
                BsLocatorMainApp()
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        // Android 13+ 需要单独请求通知权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Measure : Screen("measure", "采集", Icons.Default.LocationOn)
    data object Map : Screen("map", "地图", Icons.Default.Place)
    data object Logs : Screen("logs", "日志", Icons.Default.Book)
    data object Estimate : Screen("estimate", "推断", Icons.Default.Analytics)
}

@Composable
fun BsLocatorMainApp() {
    val navController = rememberNavController()
    val screens = listOf(Screen.Measure, Screen.Map, Screen.Logs, Screen.Estimate)
    val viewModel: MainViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
        )
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Measure.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Measure.route) { MeasureScreen(viewModel) }
            composable(Screen.Map.route) { MapScreen(viewModel) }
            composable(Screen.Logs.route) { LogsScreen(viewModel) }
            composable(Screen.Estimate.route) { EstimateScreen(viewModel) }
        }
    }
}
