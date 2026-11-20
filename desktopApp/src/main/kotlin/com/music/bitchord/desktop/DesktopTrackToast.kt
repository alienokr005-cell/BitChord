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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.logo_mark
import org.jetbrains.compose.resources.painterResource
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
            // Over borderless full-screen games if the listener wants it; never over exclusive
            // full screen, where it can't show; see [DesktopFullscreenApps]. Where see-through
            // windows can't be drawn (OpenGL, Compatibility, safe mode) it still shows, in a plain
            // window cut to the card's shape — see [DesktopTrackToastWindow].
            when (DesktopFullscreenApps.state()) {
                DesktopFullscreenApps.State.NONE -> Unit
                DesktopFullscreenApps.State.BORDERLESS -> if (!DesktopTrackCardSettings.overGames.value) {
                    DesktopTrackLog.log("now playing card: skipped, a full-screen app is up and 'over games' is off")
                    return null
                }
                DesktopFullscreenApps.State.EXCLUSIVE -> {
                    DesktopTrackLog.log("now playing card: skipped, an exclusive full-screen game has the screen")
                    return null
                }
            }
        }
        return wanted
    }
}

private const val CARD_WIDTH_DP = 400
private const val CARD_HEIGHT_DP = 120

/** The bar's light blue-grey, as on the card this one is modelled on. */
private val ClassicBar = Color(0xFFA8B8D8)

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
 * The card on the desktop, from the application scope: hands each desktop announcement to
 * [DesktopTrackOverlay], which draws it in its own see-through window.
 */
@Composable
internal fun DesktopTrackToastWindow(onOpen: () -> Unit) {
    val announcement by DesktopTrackToast.announcement.collectAsState()
    val shown = announcement?.takeIf { it.target == DesktopTrackToast.Target.SCREEN }
    LaunchedEffect(shown?.serial) {
        if (shown != null) DesktopTrackOverlay.show(shown, onOpen) else DesktopTrackOverlay.hide()
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
 * The card itself, inside BitChord's window: the same card [DesktopTrackOverlay] draws on the
 * desktop — dark acrylic with a glow of the cover's colour, the square cover with the app's mark
 * and name under it, the title, the artist, and a progress bar between the elapsed and total time.
 *
 * [TrackCardStyle.ALBUM] fills the bar with the cover's own colour; [TrackCardStyle.CLASSIC] keeps it periwinkle.
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
    val shape = RoundedCornerShape(13.dp)
    val bar = announcement.accent.takeIf { style == TrackCardStyle.ALBUM } ?: ClassicBar
    val glow = announcement.accent

    Box(
        modifier
            .size(CARD_WIDTH_DP.dp, CARD_HEIGHT_DP.dp)
            .shadow(18.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color(0xF01C1C1C))
            // The cover's colour bleeding into the glass from the left.
            .drawBehind {
                if (glow != null) {
                    drawRect(
                        Brush.radialGradient(
                            0f to glow.copy(alpha = 0.33f),
                            0.45f to glow.copy(alpha = 0.14f),
                            1f to Color.Transparent,
                            center = Offset(52.dp.toPx(), size.height / 2f),
                            radius = size.width * 0.62f,
                        ),
                    )
                }
            }
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Column(
            Modifier
                .padding(start = 16.dp, top = 14.dp)
                .width(72.dp)
                .graphicsLayer {
                    val c = cover()
                    alpha = c.coerceIn(0f, 1f)
                    val scale = 0.8f + 0.2f * c
                    scaleX = scale
                    scaleY = scale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF3A3A3C)),
            ) {
                announcement.art?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(Color.White), contentAlignment = Alignment.Center) {
                    Image(
                        painterResource(Res.drawable.logo_mark),
                        contentDescription = null,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF1C1C1C)),
                        modifier = Modifier.size(8.5.dp, 6.dp),
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text("BitChord", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
        Column(Modifier.padding(start = 104.dp, top = 22.dp, end = 16.dp)) {
            StaggeredLine(words, 0f) {
                Text(
                    song.title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StaggeredLine(words, 0.12f) {
                Text(song.artist, color = Color(0xFFB3B3B3), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(9.dp))
            StaggeredLine(words, 0.24f) {
                TrackProgress(song, bar)
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
    val timeColour = Color(0xFF9E9E9E)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(clock(positionMs), color = timeColour, fontSize = 11.5.sp)
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.weight(1f).height(4.dp)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = r)
            if (fraction > 0f) {
                drawRoundRect(accent, size = Size(size.width * fraction, size.height), cornerRadius = r)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(if (durationMs > 0L) clock(durationMs) else "–:––", color = timeColour, fontSize = 11.5.sp)
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
