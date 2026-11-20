package com.music.bitchord.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.logo_mark
import org.jetbrains.compose.resources.painterResource
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

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
     * One announcement, with everything it draws already prepared — the cover decoded and its
     * accent colour picked once — so nothing loads or samples while the card is moving.
     */
    class Announcement(
        val song: Song,
        val serial: Long,
        val target: Target,
        val art: ImageBitmap?,
        /** The cover's most vivid colour, for the progress bar and the card's wash; null without one. */
        val accent: Color?,
    )

    /**
     * The player's state, for the card's progress bar. Set once by the app when its engine exists;
     * read only while a card is up.
     */
    @Volatile var playback: StateFlow<DesktopPlaybackState>? = null

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
            val accent = art?.let(::accentColour)
            // A newer track got in first; this one is stale.
            if (mine != serial) return@launch
            current.value = Announcement(song, mine, target, art, accent)
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
            // The desktop card is a see-through window; where those can't be drawn (OpenGL,
            // Compatibility, safe mode) it would be a black box, so it stays inside the app.
            if (!DesktopStability.transparencyAllowed) return null
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

private const val CARD_WIDTH_DP = 380
private const val CARD_HEIGHT_DP = 92

/** Spotify's green: the bar's colour when the cover gives nothing better. */
private val FallbackAccent = Color(0xFF1ED760)

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
    // Not created at launch: only once a card first needs the desktop, then kept for the next.
    var needed by remember { mutableStateOf(false) }
    LaunchedEffect(shown != null) { if (shown != null) needed = true }
    if (!needed) return

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
 * one after the other, and it rests with only its progress bar moving. Out: the words go
 * first, then the card slips back to its edge, quicker than it came.
 *
 * Springs are physics rather than a timeline, so they look the same at any frame rate, and every
 * frame of all of it is a `graphicsLayer` transform or a draw-phase read — no recomposition and no
 * layout while it moves. While it rests, only the progress bar redraws, a few times a second.
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
            launch { card.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 320f)) }
            launch {
                delay(if (replacing) 0 else 90)
                cover.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 520f))
            }
            launch {
                delay(if (replacing) 40 else 170)
                words.animateTo(1f, tween(420, easing = TextEasing))
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

/**
 * The card itself, laid out like Spotify's "now playing" card: the cover on the left with a small
 * BitChord badge, the title and artist, and a progress bar between the elapsed and total time.
 *
 * [TrackCardStyle.ALBUM] washes the dark card with the cover's own colour from the left and fills
 * the bar with it; [TrackCardStyle.DARK] keeps the card plain and the bar Spotify green.
 */
@Composable
private fun TrackCard(
    announcement: DesktopTrackToast.Announcement,
    style: TrackCardStyle,
    cover: () -> Float,
    words: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = announcement.song
    val shape = RoundedCornerShape(12.dp)
    val base = Color(0xFF181818)
    val tinted = style == TrackCardStyle.ALBUM && announcement.accent != null
    val accent = if (tinted) announcement.accent!! else FallbackAccent
    val secondary = Color(0xFFB3B3B3)

    Box(
        modifier
            .size(CARD_WIDTH_DP.dp, CARD_HEIGHT_DP.dp)
            .shadow(20.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(
                if (tinted) {
                    Brush.horizontalGradient(
                        0f to lerp(base, accent, 0.30f).copy(alpha = 0.96f),
                        0.65f to base.copy(alpha = 0.94f),
                    )
                } else {
                    Brush.horizontalGradient(listOf(base.copy(alpha = 0.94f), base.copy(alpha = 0.94f)))
                },
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 10.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .graphicsLayer {
                        val c = cover()
                        alpha = c.coerceIn(0f, 1f)
                        val scale = 0.7f + 0.3f * c
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(6.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF282828)),
            ) {
                announcement.art?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                // The app's badge on the cover's corner, as Spotify badges its own.
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(start = 3.dp, end = 5.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(painterResource(Res.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("BitChord", color = Color.White, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                StaggeredLine(words, 0f) {
                    Text(
                        song.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StaggeredLine(words, 0.12f) {
                    Text(song.artist, color = secondary, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                StaggeredLine(words, 0.24f) {
                    TrackProgress(song, accent)
                }
            }
        }
    }
}

/**
 * Elapsed time, the bar, and the length, read off the player while the card is up. Dead reckoned
 * between the engine's readings, which arrive when it has something to say rather than on a beat.
 */
@Composable
private fun TrackProgress(song: Song, accent: Color) {
    val state = DesktopTrackToast.playback
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(song.videoId, state) {
        while (isActive) {
            val now = state?.value?.takeIf { it.song?.videoId == song.videoId }
            if (now != null) {
                val running = now.isPlaying && !now.awaitingAudio && now.positionSampledAtNanos > 0L
                val drift = if (running) (System.nanoTime() - now.positionSampledAtNanos) / 1_000_000L else 0L
                durationMs = now.durationMs
                positionMs = (now.positionMs + drift).coerceIn(0L, now.durationMs.coerceAtLeast(0L))
            }
            delay(250)
        }
    }
    val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val timeColour = Color(0xFFA7A7A7)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(clock(positionMs), color = timeColour, fontSize = 10.5.sp, textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).height(4.dp)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = r)
            if (fraction > 0f) {
                drawRoundRect(accent, size = Size(size.width * fraction, size.height), cornerRadius = r)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (durationMs > 0L) clock(durationMs) else "–:––",
            color = timeColour,
            fontSize = 10.5.sp,
            modifier = Modifier.width(30.dp),
        )
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1_000L).coerceAtLeast(0L)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
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
 * The cover's most vivid colour, brightened enough to read on the dark card. Sampled once per song
 * on a coarse grid: hues are binned, each pixel weighted by how saturated and bright it is, and the
 * heaviest bin's average wins. Null for a cover with nothing but greys.
 */
private fun accentColour(art: ImageBitmap): Color? = runCatching {
    val bitmap = art.asSkiaBitmap()
    val bins = 12
    val weight = FloatArray(bins)
    val r = FloatArray(bins)
    val g = FloatArray(bins)
    val b = FloatArray(bins)
    val steps = 24
    val hsv = FloatArray(3)
    for (yi in 0 until steps) {
        for (xi in 0 until steps) {
            val argb = bitmap.getColor(xi * bitmap.width / steps, yi * bitmap.height / steps)
            val pr = (argb shr 16) and 0xFF
            val pg = (argb shr 8) and 0xFF
            val pb = argb and 0xFF
            java.awt.Color.RGBtoHSB(pr, pg, pb, hsv)
            val w = hsv[1] * hsv[1] * hsv[2]
            if (w < 0.04f) continue
            val bin = ((hsv[0] * bins).toInt()).coerceIn(0, bins - 1)
            weight[bin] += w
            r[bin] += pr * w
            g[bin] += pg * w
            b[bin] += pb * w
        }
    }
    val best = weight.indices.maxByOrNull { weight[it] } ?: return@runCatching null
    if (weight[best] <= 0f) return@runCatching null
    val w = weight[best]
    java.awt.Color.RGBtoHSB((r[best] / w).toInt(), (g[best] / w).toInt(), (b[best] / w).toInt(), hsv)
    val rgb = java.awt.Color.HSBtoRGB(hsv[0], hsv[1].coerceIn(0.45f, 0.85f), hsv[2].coerceAtLeast(0.82f))
    Color(rgb or 0xFF000000.toInt())
}.getOrNull()
