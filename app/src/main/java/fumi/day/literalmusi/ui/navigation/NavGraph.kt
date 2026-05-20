package fumi.day.literalmusi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import fumi.day.literalmusi.ui.list.MusicListScreen
import fumi.day.literalmusi.ui.player.PlayerScreen
import fumi.day.literalmusi.ui.settings.SettingsScreen

object Routes {
    const val MUSIC_LIST = "music/list"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
}

@Composable
fun NavGraph(
    navController: NavHostController
) {
    NavHost(
        navController = navController,
        startDestination = Routes.MUSIC_LIST
    ) {
        composable(Routes.MUSIC_LIST) {
            MusicListScreen(
                onNavigateToPlayer = {
                    navController.navigate(Routes.PLAYER)
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.PLAYER) {
            PlayerScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
