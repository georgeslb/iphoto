package org.iphoto.mobile.ui.nav

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.sync.UploadStatus
import org.iphoto.mobile.ui.albums.AlbumDetailScreen
import org.iphoto.mobile.ui.albums.AlbumsScreen
import org.iphoto.mobile.ui.library.LibraryScreen
import org.iphoto.mobile.ui.photo.PhotoDetailScreen
import org.iphoto.mobile.ui.shares.SharesScreen
import org.iphoto.mobile.ui.spaces.SpaceDetailScreen
import org.iphoto.mobile.ui.spaces.SpacesScreen
import org.iphoto.mobile.ui.sync.AssetDetailScreen
import org.iphoto.mobile.ui.sync.SyncScreen
import org.iphoto.mobile.ui.sync.SyncViewModel
import org.iphoto.mobile.ui.sync.SyncViewModelFactory
import org.iphoto.mobile.ui.sync.UploadListDialog

@RequiresApi(Build.VERSION_CODES.P)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onLogout: () -> Unit) {
    val container = LocalAppContainer.current
    val navController = rememberNavController()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isPhotoDetail = currentRoute?.startsWith("photo") == true
    val hideAppBars = isPhotoDetail && isLandscape

    var showUploadListDialog by remember { mutableStateOf(false) }
    val uploadQueue by container.uploadManager.uploadQueue.collectAsState()
    val activeUploadCount = uploadQueue.count {
        it.status == UploadStatus.QUEUED || it.status == UploadStatus.UPLOADING || it.status == UploadStatus.PROCESSING
    }

    Scaffold(
        contentWindowInsets = if (hideAppBars) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!hideAppBars) {
                TopAppBar(
                    title = { Text("iPhoto") },
                    actions = {
                        if (uploadQueue.isNotEmpty()) {
                            IconButton(onClick = { showUploadListDialog = true }) {
                                BadgedBox(
                                    badge = {
                                        if (activeUploadCount > 0) {
                                            Badge { Text("$activeUploadCount") }
                                        }
                                    }
                                ) {
                                    if (activeUploadCount > 0) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Default.CloudDone, contentDescription = "Uploads")
                                    }
                                }
                            }
                        }
                        IconButton(onClick = onLogout) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Log out")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!hideAppBars) {
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
        }
    ) { padding ->
        val context = LocalContext.current
        val container = LocalAppContainer.current
        val syncViewModel: SyncViewModel = viewModel(
            factory = SyncViewModelFactory(context.applicationContext, container)
        )

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
                    onPhotoClick = { photoId -> navController.navigate("photo/$photoId?albumId=$albumId") },
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
                    onPhotoClick = { photoId -> navController.navigate("photo/$photoId?spaceId=$spaceId") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Destination.Shares.route) {
                SharesScreen(onAlbumClick = { albumId -> navController.navigate("albums/$albumId") })
            }
            composable(Destination.Sync.route) {
                SyncScreen(
                    viewModel = syncViewModel,
                    onAssetClick = { asset ->
                        syncViewModel.openAssetDetail(asset)
                        navController.navigate("asset-detail")
                    }
                )
            }
            composable(route = "asset-detail") {
                val asset = syncViewModel.selectedAssetForDetail
                if (asset != null) {
                    AssetDetailScreen(
                        viewModel = syncViewModel,
                        asset = asset,
                        onBack = {
                            navController.popBackStack()
                        }
                    )
                }
            }
            composable(
                // albumId/spaceId are optional context the lightbox needs to know what it can act
                // on - "remove from album" only makes sense when opened from AlbumDetailScreen,
                // and mirroring "move to garbage" onto a synced folder's local trash only applies
                // when opened from the Space currently synced to a device folder. Library's
                // onPhotoClick passes neither.
                route = "photo/{photoId}?albumId={albumId}&spaceId={spaceId}",
                arguments = listOf(
                    navArgument("photoId") { type = NavType.StringType },
                    navArgument("albumId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("spaceId") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val photoId = backStackEntry.arguments?.getString("photoId").orEmpty()
                val albumId = backStackEntry.arguments?.getString("albumId")
                val spaceId = backStackEntry.arguments?.getString("spaceId")
                PhotoDetailScreen(
                    photoId = photoId,
                    albumId = albumId,
                    spaceId = spaceId,
                    onBack = { navController.popBackStack() },
                    // Replaces this lightbox's own backstack entry with the sibling photo's,
                    // rather than pushing a new one each time - Back still goes straight to the
                    // grid no matter how many photos were paged through via the arrows.
                    onNavigateToPhoto = { nextPhotoId ->
                        val route = buildString {
                            append("photo/$nextPhotoId?")
                            if (albumId != null) append("albumId=$albumId&")
                            if (spaceId != null) append("spaceId=$spaceId")
                        }
                        navController.navigate(route) {
                            popUpTo("photo/{photoId}?albumId={albumId}&spaceId={spaceId}") { inclusive = true }
                        }
                    }
                )
            }
        }
    }

    if (showUploadListDialog) {
        UploadListDialog(
            uploadManager = container.uploadManager,
            onDismiss = { showUploadListDialog = false }
        )
    }
}
