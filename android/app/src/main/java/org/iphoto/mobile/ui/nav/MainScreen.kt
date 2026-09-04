package org.iphoto.mobile.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.iphoto.mobile.ui.albums.AlbumDetailScreen
import org.iphoto.mobile.ui.albums.AlbumsScreen
import org.iphoto.mobile.ui.library.LibraryScreen
import org.iphoto.mobile.ui.photo.PhotoDetailScreen
import org.iphoto.mobile.ui.shares.SharesScreen
import org.iphoto.mobile.ui.spaces.SpaceDetailScreen
import org.iphoto.mobile.ui.spaces.SpacesScreen

@Composable
fun MainScreen(onLogout: () -> Unit) {
    val navController = rememberNavController()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("iPhoto") },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Log out")
                    }
                }
            )
        },
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            NavigationBar {
                Destination.bottomNavTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Library.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Destination.Library.route) {
                LibraryScreen(onPhotoClick = { photoId -> navController.navigate("photo/$photoId") })
            }
            composable(Destination.Albums.route) {
                AlbumsScreen(onAlbumClick = { albumId -> navController.navigate("albums/$albumId") })
            }
            composable(
                route = "albums/{albumId}",
                arguments = listOf(navArgument("albumId") { type = NavType.StringType })
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("albumId").orEmpty()
                AlbumDetailScreen(
                    albumId = albumId,
                    onPhotoClick = { photoId -> navController.navigate("photo/$photoId") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Destination.Spaces.route) {
                SpacesScreen(onSpaceClick = { spaceId -> navController.navigate("spaces/$spaceId") })
            }
            composable(
                route = "spaces/{spaceId}",
                arguments = listOf(navArgument("spaceId") { type = NavType.StringType })
            ) { backStackEntry ->
                val spaceId = backStackEntry.arguments?.getString("spaceId").orEmpty()
                SpaceDetailScreen(
                    spaceId = spaceId,
                    onPhotoClick = { photoId -> navController.navigate("photo/$photoId") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Destination.Shares.route) {
                SharesScreen()
            }
            composable(
                route = "photo/{photoId}",
                arguments = listOf(navArgument("photoId") { type = NavType.StringType })
            ) { backStackEntry ->
                val photoId = backStackEntry.arguments?.getString("photoId").orEmpty()
                PhotoDetailScreen(photoId = photoId, onBack = { navController.popBackStack() })
            }
        }
    }
}
