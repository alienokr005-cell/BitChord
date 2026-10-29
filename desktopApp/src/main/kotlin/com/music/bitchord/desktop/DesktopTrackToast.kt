package com.music.bitchord.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Window
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "Now playing" card shown for a few seconds each time the track changes.
 *
 * Two homes, picked in Settings → Appearance: inside BitChord's window, over the page, or on the
 * desktop as its own always-on-top, never-focused window that shows even while BitChord is
 * minimised or in the tray. Corner, style and how long it stays are settings too.
 */
internal object DesktopTrackToast {

    /** One announcement; [serial] makes the same song announced twice a new one. */
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
        if (DesktopTrackCardSettings.placement.value == TrackCardPlacement.OFF) return
        current.value = Announcement(song, ++serial)
    }

    /** Shows the card now, for Settings' Preview button. */
    fun preview(song: Song) {
        current.value = Announcement(song, ++serial)
    }

    fun dismiss() {
        current.value = null
    }
}

private const val CARD_WIDTH_DP = 340
private const val CARD_HEIGHT_DP = 84

/** Room around the card in the desktop window, for its shadow and its slide. */
private const val SCREEN_WINDOW_PAD_DP = 18
private const val SCREEN_EDGE_MARGIN_PX = 8

private const val ENTER_MS = 460
private const val EXIT_MS = 340

/** Material's emphasized curves: a long, soft settle in; a quick, clean exit. */
private val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** The card over BitChord's own page. Draw it last, over everything, in a full-size box. */
@Composable
internal fun BoxScope.DesktopTrackCardInApp() {
    val placement by DesktopTrackCardSettings.placement.collectAsState()
    if (placement != TrackCardPlacement.IN_APP) return
    val announcement by DesktopTrackToast.announcement.collectAsState()
    val corner by DesktopTrackCardSettings.corner.collectAsState()
    TrackCardPresenter(
        announcement = announcement,
        corner = corner,
        onClick = {},
        modifier = Modifier
            .align(corner.alignment())
            // Clear of the top bar above and the player bar below.
            .padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 104.dp),
    )
}

/** The card on the desktop, in its own window. Call it from the application scope. */
@Composable
internal fun DesktopTrackToastWindow(onOpen: () -> Unit) {
    val placement by DesktopTrackCardSettings.placement.collectAsState()
    val announcement by DesktopTrackToast.announcement.collectAsState()
    val corner by DesktopTrackCardSettings.corner.collectAsState()
    if (placement != TrackCardPlacement.SCREEN) return

    Window(
        visible = announcement != null,
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
                val pad = SCREEN_WINDOW_PAD_DP * 2
                size = java.awt.Dimension(CARD_WIDTH_DP + pad, CARD_HEIGHT_DP + pad)
            }
        },
        dispose = ComposeWindow::dispose,
        update = { window -> window.setLocation(screenLocation(window.width, window.height, corner)) },
    ) {
        Box(Modifier.fillMaxSize().padding(SCREEN_WINDOW_PAD_DP.dp)) {
            TrackCardPresenter(
                announcement = announcement,
                corner = corner,
                onClick = onOpen,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * Shows [announcement], holds it for the chosen time, and slides it away.
 *
 * Every frame of the slide is a `graphicsLayer` transform read in the draw phase: no
 * recomposition and no layout per frame, so the card moves at whatever rate the display refreshes —
 * 144 Hz on a 144 Hz monitor — rather than at the pace of the UI's work.
 */
@Composable
private fun TrackCardPresenter(
    announcement: DesktopTrackToast.Announcement?,
    corner: TrackCardCorner,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style by DesktopTrackCardSettings.style.collectAsState()
    val duration by DesktopTrackCardSettings.duration.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val appear = remember { Animatable(0f) }
    val countdown = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<DesktopTrackToast.Announcement?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun leave() {
        appear.animateTo(0f, tween(if (reduceAnimation) 150 else EXIT_MS, easing = ExitEasing))
        shown = null
        DesktopTrackToast.dismiss()
    }

    LaunchedEffect(announcement?.serial) {
        val next = announcement ?: return@LaunchedEffect
        shown = next
        countdown.snapTo(0f)
        launch { appear.animateTo(1f, tween(if (reduceAnimation) 150 else ENTER_MS, easing = EnterEasing)) }
        countdown.animateTo(1f, tween(duration.millis.toInt(), easing = LinearEasing))
        leave()
    }

    val song = shown?.song ?: return
    TrackCard(
        song = song,
        style = style,
        countdown = { countdown.value },
        onClick = {
            onClick()
            scope.launch { leave() }
        },
        modifier = modifier.graphicsLayer {
            val p = appear.value
            alpha = p
            if (!reduceAnimation) {
                val remaining = 1f - p
                when (corner.horizontal) {
                    0 -> translationY = remaining * size.height * 0.9f * (if (corner.top) -1f else 1f)
                    else -> translationX = remaining * size.width * 0.55f * corner.horizontal
                }
                val scale = 0.94f + 0.06f * p
                scaleX = scale
                scaleY = scale
            }
        },
    )
}

/** The card itself, in each [TrackCardStyle]. */
@Composable
internal fun TrackCard(
    song: Song,
    style: TrackCardStyle,
    countdown: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val light = style == TrackCardStyle.LIGHT
    val primary = if (light) Color(0xFF111114) else Color.White
    val secondary = if (light) Color(0xFF111114).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.68f)
    val accent = Color(0xFFFA2D48)

    Box(
        modifier
            .size(CARD_WIDTH_DP.dp, CARD_HEIGHT_DP.dp)
            .shadow(elevation(style), shape, clip = false)
            .clip(shape)
            .background(
                when (style) {
                    TrackCardStyle.GLOSSY -> Brush.verticalGradient(listOf(Color(0xFF34343F), Color(0xFF15151A)))
                    TrackCardStyle.GLASS -> Brush.verticalGradient(listOf(Color(0xFF202027), Color(0xFF202027)))
                    TrackCardStyle.SOLID -> Brush.verticalGradient(listOf(Color(0xFF1C1C22), Color(0xFF1C1C22)))
                    TrackCardStyle.LIGHT -> Brush.verticalGradient(listOf(Color(0xFFFDFDFE), Color(0xFFEDEDF1)))
                },
            )
            .clickable(onClick = onClick),
    ) {
        if (style == TrackCardStyle.GLASS) {
            // The cover itself, blown up and blurred, under a dark wash: frosted glass tinted
            // by the song.
            DesktopArtwork(
                song.thumbnailUrl,
                Modifier.matchParentSize().blur(26.dp).graphicsLayer { scaleX = 1.4f; scaleY = 1.4f },
                contentScale = ContentScale.Crop,
                px = ROW_ART_PX,
            )
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.42f)))
        }
        if (style == TrackCardStyle.GLOSSY || style == TrackCardStyle.GLASS) {
            // The gloss: a soft highlight across the top half, as on polished plastic.
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = if (style == TrackCardStyle.GLOSSY) 0.16f else 0.12f), Color.Transparent),
                        ),
                    ),
            )
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopArtwork(
                song.thumbnailUrl,
                Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)),
                px = ROW_ART_PX,
            )
            Column(
                Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text("NOW PLAYING", color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                Text(
                    song.title,
                    color = primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(song.artist, color = secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // How long is left, as a hairline along the bottom. Drawn, not laid out, every frame.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .drawBehind {
                    val left = (1f - countdown()).coerceIn(0f, 1f)
                    drawRect(accent.copy(alpha = 0.85f), Offset.Zero, Size(size.width * left, size.height))
                },
        )
        // A thin edge: brighter at the top for the glossy styles, catching the light.
        Box(
            Modifier.matchParentSize().border(
                1.dp,
                when (style) {
                    TrackCardStyle.LIGHT -> Brush.verticalGradient(listOf(Color.Black.copy(0.08f), Color.Black.copy(0.08f)))
                    TrackCardStyle.SOLID -> Brush.verticalGradient(listOf(Color.White.copy(0.08f), Color.White.copy(0.08f)))
                    else -> Brush.verticalGradient(listOf(Color.White.copy(0.38f), Color.White.copy(0.06f)))
                },
                shape,
            ),
        )
    }
}

private fun elevation(style: TrackCardStyle): Dp = if (style == TrackCardStyle.LIGHT) 10.dp else 14.dp

private fun TrackCardCorner.alignment(): Alignment = when (this) {
    TrackCardCorner.TOP_LEFT -> Alignment.TopStart
    TrackCardCorner.TOP_CENTER -> Alignment.TopCenter
    TrackCardCorner.TOP_RIGHT -> Alignment.TopEnd
    TrackCardCorner.BOTTOM_LEFT -> Alignment.BottomStart
    TrackCardCorner.BOTTOM_CENTER -> Alignment.BottomCenter
    TrackCardCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
}

/**
 * The desktop window's spot in [corner] of the primary screen's usable area, clear of the taskbar,
 * in AWT's own coordinates — the same ones `setLocation` takes, whatever the display scaling.
 */
private fun screenLocation(width: Int, height: Int, corner: TrackCardCorner): java.awt.Point {
    val config = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val left = bounds.x + insets.left + SCREEN_EDGE_MARGIN_PX
    val right = bounds.x + bounds.width - insets.right - width - SCREEN_EDGE_MARGIN_PX
    val top = bounds.y + insets.top + SCREEN_EDGE_MARGIN_PX
    val bottom = bounds.y + bounds.height - insets.bottom - height - SCREEN_EDGE_MARGIN_PX
    val x = when (corner.horizontal) {
        -1 -> left
        1 -> right
        else -> (left + right) / 2
    }
    return java.awt.Point(x, if (corner.top) top else bottom)
}
