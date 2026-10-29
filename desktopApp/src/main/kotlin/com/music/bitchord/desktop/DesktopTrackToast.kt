package com.music.bitchord.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A small "Now playing" card at the bottom-right corner of the screen, shown for a few seconds
 * each time the track changes.
 *
 * It is its own always-on-top window rather than part of the app's, so it shows while BitChord is
 * minimised, behind other windows or put away in the tray. It never takes focus, so a song change
 * mid-typing elsewhere doesn't steal the keyboard. Clicking it brings BitChord to the front.
 */
internal object DesktopTrackToast {

    /** One announcement; [serial] makes the same song played twice in a row a new one. */
    data class Announcement(val song: Song, val serial: Long)

    private val current = MutableStateFlow<Announcement?>(null)
    val announcement: StateFlow<Announcement?> = current.asStateFlow()

    private var lastVideoId: String? = null
    private var serial = 0L

    /** Called whenever the playing track or play state changes; announces only a new track. */
    fun onTrack(song: Song?, isPlaying: Boolean) {
        if (song == null || !isPlaying) return
        if (song.videoId == lastVideoId) return
        lastVideoId = song.videoId
        current.value = Announcement(song, ++serial)
    }

    fun dismiss() {
        current.value = null
    }
}

private val TOAST_SIZE = DpSize(340.dp, 84.dp)
private const val TOAST_SHOW_MS = 4_500L
private const val TOAST_MARGIN_PX = 16

/** The toast's window. Lives for the whole app; only shown while there is something to announce. */
@Composable
internal fun ApplicationScope.DesktopTrackToastWindow(onOpen: () -> Unit) {
    val announcement by DesktopTrackToast.announcement.collectAsState()
    val visibility = remember { MutableTransitionState(false) }

    LaunchedEffect(announcement) {
        if (announcement == null) return@LaunchedEffect
        visibility.targetState = true
        delay(TOAST_SHOW_MS)
        visibility.targetState = false
    }
    // Close the window once the slide-out has finished, so nothing invisible sits on top of the
    // desktop catching clicks.
    val windowVisible = announcement != null && (visibility.targetState || !visibility.isIdle)
    LaunchedEffect(visibility.isIdle, visibility.targetState) {
        if (visibility.isIdle && !visibility.targetState && announcement != null) DesktopTrackToast.dismiss()
    }

    Window(
        visible = windowVisible,
        create = {
            ComposeWindow().apply {
                // Set before the window is shown — AWT refuses these on a displayable window.
                type = java.awt.Window.Type.UTILITY // no taskbar button
                isUndecorated = true
                isTransparent = true
                background = java.awt.Color(0, 0, 0, 0)
                isAlwaysOnTop = true
                focusableWindowState = false
                isResizable = false
                title = "BitChord – Now playing"
                size = java.awt.Dimension(TOAST_SIZE.width.value.toInt(), TOAST_SIZE.height.value.toInt())
            }
        },
        dispose = ComposeWindow::dispose,
        update = { window ->
            // Re-placed on each announcement, in case the screen layout changed since the last.
            window.setLocation(toastLocation(window.width, window.height))
        },
    ) {
        val shown = announcement ?: return@Window
        AnimatedVisibility(
            visibleState = visibility,
            enter = slideInHorizontally(tween(260)) { it / 2 } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(260)) { it / 2 } + fadeOut(tween(220)),
        ) {
            TrackToastCard(shown.song) {
                visibility.targetState = false
                onOpen()
            }
        }
    }
}

@Composable
private fun TrackToastCard(song: Song, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .clip(shape)
            .background(Color(0xF01C1C22))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DesktopArtwork(
                song.thumbnailUrl,
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                px = ROW_ART_PX,
            )
            Column(
                Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    "NOW PLAYING",
                    color = Color(0xFFFA2D48),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                )
                Text(
                    song.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Bottom-right of the primary screen's usable area, clear of the taskbar, in AWT's own
 * coordinates — the same ones `setLocation` takes, whatever the display scaling.
 */
private fun toastLocation(width: Int, height: Int): java.awt.Point {
    val config = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val x = bounds.x + bounds.width - insets.right - width - TOAST_MARGIN_PX
    val y = bounds.y + bounds.height - insets.bottom - height - TOAST_MARGIN_PX
    return java.awt.Point(x, y)
}
