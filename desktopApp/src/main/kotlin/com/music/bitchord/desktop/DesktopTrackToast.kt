package com.music.bitchord.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * The "Now playing" card shown for a few seconds each time the track changes.
 *
 * Set to show inside BitChord, it does — while BitChord's window is in front. Minimised, in the
 * tray or behind another window, the same card shows on the desktop instead, in its own
 * always-on-top window that never takes focus. Set to show on the desktop, it always does.
 */
internal object DesktopTrackToast {

    enum class Target { IN_APP, SCREEN }

    /**
     * One announcement, with everything it draws already prepared — the cover decoded and, for the
     * glass style, its blur rendered once — so nothing loads or blurs while the card is moving.
     */
    class Announcement(
        val song: Song,
        val serial: Long,
        val target: Target,
        val art: ImageBitmap?,
        val backdrop: ImageBitmap?,
    )

    private val current = MutableStateFlow<Announcement?>(null)
    val announcement: StateFlow<Announcement?> = current.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var lastVideoId: String? = null
    @Volatile private var serial = 0L

    /** Called whenever the playing track or play state changes; announces only a new track. */
    fun onTrack(song: Song?, isPlaying: Boolean) {
        if (song == null || !isPlaying) return
        if (song.videoId == lastVideoId) return
        lastVideoId = song.videoId
        announce(song)
    }

    /** Shows the card now, for Settings' Preview button. */
    fun preview(song: Song) = announce(song)

    fun dismiss() {
        current.value = null
    }

    private fun announce(song: Song) {
        val target = target() ?: return
        val mine = ++serial
        scope.launch {
            // Prepared before the card moves; given up on after a moment so a slow network
            // doesn't hold the announcement back — the card then shows a placeholder tile.
            val art = withTimeoutOrNull(1_500) {
                withContext(Dispatchers.IO) { DesktopArtworkCache.load(song.thumbnailUrl.artworkAt(CARD_ART_PX)) }
            }
            val backdrop = if (art != null && DesktopTrackCardSettings.style.value == TrackCardStyle.GLASS) {
                blurredBackdrop(art)
            } else {
                null
            }
            // A newer track got in first; this one is stale.
            if (mine != serial) return@launch
            current.value = Announcement(song, mine, target, art, backdrop)
        }
    }

    /** Where this announcement shows, or null when it shouldn't show at all. */
    private fun target(): Target? {
        val wanted = when (DesktopTrackCardSettings.placement.value) {
            TrackCardPlacement.OFF -> return null
            TrackCardPlacement.SCREEN -> Target.SCREEN
            // Inside the app only while the app can be seen; otherwise on the desktop.
            TrackCardPlacement.IN_APP -> if (DesktopWindowVisibility.inFront()) Target.IN_APP else Target.SCREEN
        }
        if (wanted == Target.SCREEN) {
            // Over borderless full-screen games if the listener wants it; never over exclusive
            // full screen, where it can't show; see [DesktopFullscreenApps].
            when (DesktopFullscreenApps.state()) {
                DesktopFullscreenApps.State.NONE -> Unit
                DesktopFullscreenApps.State.BORDERLESS -> if (!DesktopTrackCardSettings.overGames.value) return null
                DesktopFullscreenApps.State.EXCLUSIVE -> return null
            }
        }
        return wanted
    }
}

private const val CARD_WIDTH_DP = 360
private const val CARD_HEIGHT_DP = 88

/**
 * Transparent room around the card in its desktop window, for the shadow and the slide. The window
 * hangs this far past the screen edge, so the card glides in from the very edge of the screen.
 */
private const val SCREEN_WINDOW_PAD_DP = 56

/** The card's resting gap from the screen edge (past the taskbar). */
private const val SCREEN_EDGE_MARGIN = 16

/** How far the card travels on its way in, from the edge it belongs to. */
private const val TRAVEL_DP = 44f

private val ExitEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
private val TextEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** The card over BitChord's own page. Draw it last, over everything, in a full-size box. */
@Composable
internal fun BoxScope.DesktopTrackCardInApp() {
    val announcement by DesktopTrackToast.announcement.collectAsState()
    val corner by DesktopTrackCardSettings.corner.collectAsState()
    TrackCardPresenter(
        announcement = announcement?.takeIf { it.target == DesktopTrackToast.Target.IN_APP },
        corner = corner,
        onClick = {},
        modifier = Modifier
            .align(corner.alignment())
            // Clear of the top bar above and the player bar below.
            .padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 104.dp),
    )
}

/**
 * The card on the desktop, in its own window. Call it from the application scope. The window only
 * exists while the card can show there, and is only visible while a card is.
 */
@Composable
internal fun DesktopTrackToastWindow(onOpen: () -> Unit) {
    val placement by DesktopTrackCardSettings.placement.collectAsState()
    val announcement by DesktopTrackToast.announcement.collectAsState()
    val corner by DesktopTrackCardSettings.corner.collectAsState()
    if (placement == TrackCardPlacement.OFF) return
    val shown = announcement?.takeIf { it.target == DesktopTrackToast.Target.SCREEN }

    Window(
        visible = shown != null,
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
        update = { window -> window.setLocation(screenLocation(corner)) },
    ) {
        Box(Modifier.fillMaxSize().padding(SCREEN_WINDOW_PAD_DP.dp)) {
            TrackCardPresenter(
                announcement = shown,
                corner = corner,
                // Mid-game, a stray click on the card shouldn't pull BitChord over the game.
                onClick = { if (!DesktopFullscreenApps.active()) onOpen() },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * The card's entrance, stay and exit.
 *
 * In: the card glides in from its edge on a spring that settles without a wobble, growing from
 * 94% as it fades up; the cover pops a beat later with a touch of bounce; title and artist follow,
 * one after the other; then one sheen sweeps across the glass and it rests. Out: the words go
 * first, then the card slips back to its edge, quicker than it came.
 *
 * Springs are physics rather than a timeline, so they look the same at any frame rate, and every
 * frame of all of it is a `graphicsLayer` transform or a draw-phase read — no recomposition and no
 * layout while it moves. While it rests, nothing animates and no frames are drawn.
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
    val card = remember { Animatable(0f) }
    val cover = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val sheen = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<DesktopTrackToast.Announcement?>(null) }
    val scope = rememberCoroutineScope()

    /** [release]: also clear the announcement, when it was this card's to clear. */
    suspend fun leave(release: Boolean = true) {
        if (reduceAnimation) {
            card.animateTo(0f, tween(180))
        } else {
            scope.launch { words.animateTo(0f, tween(140, easing = ExitEasing)) }
            scope.launch { cover.animateTo(0f, tween(220, delayMillis = 40, easing = ExitEasing)) }
            card.animateTo(0f, tween(300, delayMillis = 70, easing = ExitEasing))
        }
        shown = null
        if (release) DesktopTrackToast.dismiss()
    }

    LaunchedEffect(announcement?.serial) {
        val next = announcement ?: run {
            // The next card went to the other place (desktop rather than app, or back); this one
            // bows out without touching that one's announcement.
            if (shown != null) leave(release = false)
            return@LaunchedEffect
        }
        val replacing = shown != null
        shown = next
        if (reduceAnimation) {
            cover.snapTo(1f)
            words.snapTo(1f)
            card.animateTo(1f, tween(180))
        } else {
            // A new song while a card is up: the words change in place rather than the card
            // leaving and coming back.
            if (replacing) words.snapTo(0f) else {
                cover.snapTo(0f)
                words.snapTo(0f)
            }
            sheen.snapTo(0f)
            launch { card.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 320f)) }
            launch {
                delay(if (replacing) 0 else 90)
                cover.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 520f))
            }
            launch {
                delay(if (replacing) 40 else 170)
                words.animateTo(1f, tween(420, easing = TextEasing))
            }
            if (style != TrackCardStyle.LIGHT && style != TrackCardStyle.SOLID) {
                delay(520)
                sheen.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
            }
        }
        delay(duration.millis)
        leave()
    }

    val current = shown ?: return
    val travel = TRAVEL_DP
    TrackCard(
        announcement = current,
        style = style,
        cover = { cover.value },
        words = { words.value },
        sheen = { sheen.value },
        onClick = {
            onClick()
            scope.launch { leave() }
        },
        modifier = modifier.graphicsLayer {
            val p = card.value
            alpha = p.coerceIn(0f, 1f)
            if (!reduceAnimation) {
                val away = (1f - p) * travel * density
                when (corner.horizontal) {
                    0 -> translationY = away * (if (corner.top) -1f else 1f)
                    else -> translationX = away * corner.horizontal
                }
                val scale = 0.94f + 0.06f * p
                scaleX = scale
                scaleY = scale
                transformOrigin = corner.origin()
            }
        },
    )
}

/** The card itself, in each [TrackCardStyle]. */
@Composable
private fun TrackCard(
    announcement: DesktopTrackToast.Announcement,
    style: TrackCardStyle,
    cover: () -> Float,
    words: () -> Float,
    sheen: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = announcement.song
    val shape = RoundedCornerShape(20.dp)
    val glass = style == TrackCardStyle.GLASS && announcement.backdrop != null
    val light = style == TrackCardStyle.LIGHT
    val primary = if (light) Color(0xFF0E0E12) else Color.White
    val secondary = if (light) Color(0xFF0E0E12).copy(alpha = 0.58f) else Color.White.copy(alpha = 0.66f)
    val accent = Color(0xFFFA2D48)

    Box(
        modifier
            .size(CARD_WIDTH_DP.dp, CARD_HEIGHT_DP.dp)
            .shadow(if (light) 18.dp else 24.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(
                when {
                    glass -> Brush.verticalGradient(listOf(Color(0xFF16161B), Color(0xFF16161B)))
                    style == TrackCardStyle.SOLID -> Brush.verticalGradient(listOf(Color(0xFF1B1B21), Color(0xFF1B1B21)))
                    light -> Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF0F0F4)))
                    else -> Brush.verticalGradient(listOf(Color(0xFF2E2E38), Color(0xFF121216)))
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            // The sheen: one bright diagonal band crossing the card once, drawn over everything.
            .drawWithContent {
                drawContent()
                val s = sheen()
                if (s <= 0f || s >= 1f) return@drawWithContent
                val band = size.width * 0.35f
                val x = -band + (size.width + band * 2) * s
                drawRect(
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = if (light) 0.5f else 0.16f),
                        1f to Color.Transparent,
                        start = Offset(x - band, 0f),
                        end = Offset(x + band * 0.4f, size.height),
                    ),
                )
            },
    ) {
        if (glass) {
            // Frosted glass tinted by the song: its cover, blurred once beforehand, under a wash.
            Image(
                announcement.backdrop!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.55f))),
                ),
            )
        }
        if (!light && style != TrackCardStyle.SOLID) {
            // The gloss: a soft highlight over the top half, as on polished glass.
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.52f)
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.Transparent))),
            )
        }
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        val c = cover()
                        alpha = c.coerceIn(0f, 1f)
                        val scale = 0.7f + 0.3f * c
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(8.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (light) Color(0xFFE2E2E8) else Color(0xFF2A2A33)),
            ) {
                announcement.art?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StaggeredLine(words, 0f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(accent))
                        Spacer(Modifier.width(6.dp))
                        Text("NOW PLAYING", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    }
                }
                StaggeredLine(words, 0.12f) {
                    Text(
                        song.title,
                        color = primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StaggeredLine(words, 0.24f) {
                    Text(song.artist, color = secondary, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // The rim: bright along the top where the light catches it, fading down the sides.
        Box(
            Modifier.matchParentSize().border(
                1.dp,
                if (light) {
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.10f)))
                } else {
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.05f)))
                },
                shape,
            ),
        )
    }
}

/** One line of the card's text, arriving [lag] of the way behind the first. */
@Composable
private fun StaggeredLine(progress: () -> Float, lag: Float, content: @Composable () -> Unit) {
    Box(
        Modifier.graphicsLayer {
            val t = ((progress() - lag) / (1f - lag)).coerceIn(0f, 1f)
            alpha = t
            translationX = (1f - t) * 14f * density
        },
    ) { content() }
}

private fun TrackCardCorner.alignment(): Alignment = when (this) {
    TrackCardCorner.TOP_LEFT -> Alignment.TopStart
    TrackCardCorner.TOP_CENTER -> Alignment.TopCenter
    TrackCardCorner.TOP_RIGHT -> Alignment.TopEnd
    TrackCardCorner.BOTTOM_LEFT -> Alignment.BottomStart
    TrackCardCorner.BOTTOM_CENTER -> Alignment.BottomCenter
    TrackCardCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
}

/** Grow from the edge the card comes in from. */
private fun TrackCardCorner.origin() = androidx.compose.ui.graphics.TransformOrigin(
    pivotFractionX = when (horizontal) { -1 -> 0f; 1 -> 1f; else -> 0.5f },
    pivotFractionY = if (horizontal == 0) (if (top) 0f else 1f) else 0.5f,
)

/**
 * Where the card's window goes so the card rests [SCREEN_EDGE_MARGIN] inside [corner] of the
 * primary screen's usable area, clear of the taskbar. The window's transparent padding hangs past
 * the edge. AWT's own coordinates — the ones `setLocation` takes, whatever the display scaling.
 */
private fun screenLocation(corner: TrackCardCorner): java.awt.Point {
    val config = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val left = bounds.x + insets.left + SCREEN_EDGE_MARGIN
    val right = bounds.x + bounds.width - insets.right - SCREEN_EDGE_MARGIN - CARD_WIDTH_DP
    val top = bounds.y + insets.top + SCREEN_EDGE_MARGIN
    val bottom = bounds.y + bounds.height - insets.bottom - SCREEN_EDGE_MARGIN - CARD_HEIGHT_DP
    val cardX = when (corner.horizontal) {
        -1 -> left
        1 -> right
        else -> (left + right) / 2
    }
    val cardY = if (corner.top) top else bottom
    return java.awt.Point(cardX - SCREEN_WINDOW_PAD_DP, cardY - SCREEN_WINDOW_PAD_DP)
}

/**
 * The cover, centre-cropped to the card's shape and blurred, rendered once at half size on the CPU
 * — so the glass style costs the GPU nothing per frame while the card moves.
 */
private fun blurredBackdrop(art: ImageBitmap): ImageBitmap? = runCatching {
    val image = org.jetbrains.skia.Image.makeFromBitmap(art.asSkiaBitmap())
    val w = CARD_WIDTH_DP / 2
    val h = CARD_HEIGHT_DP / 2
    val aspect = w.toFloat() / h
    val sw = image.width.toFloat()
    val sh = image.height.toFloat()
    val cropH = minOf(sh, sw / aspect)
    val cropW = cropH * aspect
    val source = Rect.makeXYWH((sw - cropW) / 2f, (sh - cropH) / 2f, cropW, cropH)
    val surface = Surface.makeRasterN32Premul(w, h)
    try {
        val paint = Paint().apply { imageFilter = ImageFilter.makeBlur(7f, 7f, FilterTileMode.CLAMP) }
        surface.canvas.drawImageRect(image, source, Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.LINEAR, paint, true)
        surface.makeImageSnapshot().toComposeImageBitmap()
    } finally {
        surface.close()
        image.close()
    }
}.getOrNull()
