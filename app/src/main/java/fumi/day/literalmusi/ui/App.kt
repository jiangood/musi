package fumi.day.literalmusi.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import fumi.day.literalmusi.ui.navigation.NavGraph
import fumi.day.literalmusi.ui.theme.LiteralMusiTheme

@Composable
fun App() {
    LiteralMusiTheme {
        val navController = rememberNavController()
        NavGraph(navController = navController)
    }
}
