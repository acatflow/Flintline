package com.tvvpn.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tvvpn.app.ui.home.MainScreen
import com.tvvpn.app.ui.nodes.NodeSelectScreen
import com.tvvpn.app.ui.remote.RemoteDesktopScreen
import com.tvvpn.app.ui.settings.SettingsScreen
import com.tvvpn.app.ui.theme.FlintLineTheme

private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_NODE_SELECT = "node_select"
private const val ROUTE_REMOTE = "remote_desktop"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlintLineTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = ROUTE_HOME) {
                        composable(ROUTE_HOME) {
                            MainScreen(
                                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                                onOpenNodeSelect = { navController.navigate(ROUTE_NODE_SELECT) },
                            )
                        }
                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onOpenRemote = { navController.navigate(ROUTE_REMOTE) },
                            )
                        }
                        composable(ROUTE_REMOTE) {
                            RemoteDesktopScreen(onBack = { navController.popBackStack() })
                        }
                        composable(ROUTE_NODE_SELECT) {
                            NodeSelectScreen()
                        }
                    }
                }
            }
        }
    }
}
