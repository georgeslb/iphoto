package org.iphoto.mobile.ui.photo

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Lightbox video playback (PhotoDetailScreen) - the counterpart to the web app's own
 * `<video controls autoPlay>` element (Lightbox.jsx), both pointed at the same
 * `GET /api/photos/{id}/stream` endpoint (PhotoController#streamVideo, which supports HTTP range
 * requests for seeking). Coil's AsyncImage - used for everything else in the lightbox - has no
 * video decode path at all, hence this separate path.
 *
 * [dataSourceFactory] should be one built from the app's own authenticated OkHttpClient (see
 * AppContainer#videoDataSourceFactory) - the stream endpoint needs the same Bearer token header
 * as every other request, which ExoPlayer's default HTTP stack wouldn't carry on its own.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(streamUrl: String, dataSourceFactory: DataSource.Factory, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(streamUrl) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                playWhenReady = true
                prepare()
            }
    }

    // Tied to the ExoPlayer instance above (which itself is keyed on streamUrl), not just Unit -
    // navigating to a different photoId recreates both together rather than leaking the old
    // player's decoder/surface resources into the new one.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(exoPlayer, lifecycleOwner) {
        // Composing this screen doesn't get torn down just because the app is backgrounded (the
        // user locks the screen, or switches away mid-video) - only navigating off this screen
        // does, via the release() below. Without pausing here too, ExoPlayer would keep decoding
        // and playing indefinitely in the background - a real, needless battery (and data) drain
        // for exactly the kind of app that plays video. No matching resume-on-ON_START: the user
        // coming back to a paused video and tapping play again (PlayerView's own controls) is a
        // simpler, safer contract than tracking "was this actually playing before we paused it".
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                player = exoPlayer
                useController = true
            }
        }
    )
}
