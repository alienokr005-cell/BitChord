package com.music.bitchord.desktop

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import bitchord.desktopapp.generated.resources.Res
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM

/** The bar in the Classic style: the reference's soft periwinkle. */
private val CLASSIC_BAR = Color(0x9D, 0xB9, 0xDC)

/** The bar for a cover with no colour to speak of — a black-and-white one. */
private val GREY_BAR = Color(0xE2, 0xE2, 0xE2)

/**
 * The "Now playing" card on the desktop, copied from the Spotify card in Discord's game overlay,
 * measured frame by frame off a recording of it:
 *
 * - The card is a half-dark tint over whatever is behind it. On that lies the song's own cover,
 *   at nearly the card's width, lightly blurred, darkened and made more saturated, so its colour
 *   reads deep rather than muddy. It is strongest from the cover to the total time and thins to a
 *   third at both ends, where the screen behind shows through tinted by the cover's colour.
 * - The cover sits at the top left with the app's mark and name under it; the title, the artist,
 *   and a progress bar between the elapsed and total time sit to its right. The bar is a soft
 *   pastel of the cover's colour.
 * - Paused, the cover dims almost to black under a white pause glyph, and the mark dims with it.
 * - On a new song the words fade out and the new ones in, the cover and the glass cross-fade, and
 *   the bar glides to its new place and colour.
 *
 * Drawn with plain Java2D in a per-pixel translucent Swing window rather than with Compose: a
 * see-through Compose window needs the GPU renderer to draw into a transparent surface, which
 * fails on some machines and every non-default renderer. Java2D's translucent windows go through
 * Windows' own layered-window path on any renderer, and cost a running game no GPU time.
 * Everything here runs on the AWT event thread.
 */
internal object DesktopTrackOverlay {

    // Measured off the reference at 1600 px wide, and scaled by 1.2 for a typical 1920 px screen.
    private const val W = 408
    private const val H = 98
    private const val RADIUS = 9f
    private const val COVER_X = 19
    private const val COVER_Y = 12
    private const val COVER_S = 61
    private const val TEXT_X = 113
    private const val RIGHT_PAD = 17

    // Where the cover's ground fades in from the left and out at the right, and its short soft
    // top: outside these the card is a dark tint over the live screen behind it. Measured.
    private const val LEFT_FADE_FROM = 18f
    private const val LEFT_FADE_TO = 40f
    private const val RIGHT_FADE_FROM = 371f
    private const val RIGHT_FADE_TO = 395f
    private const val TOP_FADE = 6f

    // The ground, fitted by least squares to every background pixel of three reference cards: the
    // cover at this share of the card's width, centred, its middle this far down the card; blurred
    // with this Gaussian sigma (px); darkened to this brightness. About 2.6–3.8 levels RMS off.
    private const val GROUND_COVER_WIDTH = 0.88f
    private const val GROUND_COVER_CENTRE_Y = 0.1f
    private const val GROUND_BLUR = 9.1f
    private const val GROUND_BRIGHTNESS = 0.23f

    /**
     * How much the ground's colour is pushed from grey, clamped: a pink cover gives a deep crimson
     * card, not a muddy one, while an orange one, already saturated, can't overshoot. Fitted on
     * four reference cards with the brightness above.
     */
    private const val GROUND_SATURATION = 2.2f

    /** How much of the cover's colour still reaches the card's very ends, over the screen behind. */
    private const val EDGE_COLOUR = 0.3f

    /** The tint over the screen behind, where the cover's ground isn't: about half. */
    private val TINT = Color(0, 0, 0, 128)

    /** Transparent room around the card for the slide in from the edge; clicks pass through it. */
    private const val PAD = 28
    private const val EDGE_MARGIN = 16
    private const val SLIDE = 22f
    private const val IN_MS = 260f
    private const val OUT_MS = 200f

    // The song change, as the reference does it.
    private const val TEXT_OUT_MS = 110f
    private const val TEXT_IN_DELAY_MS = 130f
    private const val TEXT_IN_MS = 200f
    private const val COVER_FADE_MS = 380f
    private const val BAR_GLIDE_MS = 450f
    private const val SWAP_MS = 460f
    private const val PAUSE_FADE_MS = 200f

    private var window: JWindow? = null
    private val panel = CardPanel()

    /** One song as the card draws it, with its pictures prepared once. */
    private class Face(
        val announcement: DesktopTrackToast.Announcement,
        val cover: BufferedImage?,
        /** The cover blurred over the card, darkened, with its edges feathered away. */
        val glass: BufferedImage?,
        val bar: Color,
    )

    private var face: Face? = null
    private var oldFace: Face? = null
    private var swapStart = 0L

    /** The bar as last drawn, so a new song can glide on from it. */
    private var drawnFraction = 0f
    private var drawnBar: Color = CLASSIC_BAR
    private var barFromFraction = 0f
    private var barFrom: Color = CLASSIC_BAR

    private var corner: TrackCardCorner = TrackCardCorner.BOTTOM_RIGHT
    private var onOpen: () -> Unit = {}

    /** 0 = gone, 1 = resting. */
    private var appear = 0f
    private var appearFrom = 0f
    private var appearTo = 0f
    private var appearStart = 0L
    private var appearMs = 1f
    private var afterAppear: (() -> Unit)? = null

    /** 0 = playing, 1 = paused; follows the player. */
    private var paused = 0f
    private var lastFrame = 0L

    /** Runs only while something moves; the clock below keeps the times and bar current otherwise. */
    private val frameTimer: Timer = Timer(15) { frame() }
    private val clockTimer: Timer = Timer(250) { tick() }
    private val stayTimer: Timer = Timer(5_000) { hide(release = true) }.apply { isRepeats = false }

    @Volatile private var logo: BufferedImage? = null

    /** The cover's ground's alpha over the card, by pixel: fading in from the left, out at the right. */
    private val groundAlpha: FloatArray by lazy {
        FloatArray(W * H) { i ->
            val x = i % W + 0.5f
            val y = i / W + 0.5f
            val left = EDGE_COLOUR + (1f - EDGE_COLOUR) * smooth(LEFT_FADE_FROM, LEFT_FADE_TO, x)
            val right = EDGE_COLOUR + (1f - EDGE_COLOUR) * (1f - smooth(RIGHT_FADE_FROM, RIGHT_FADE_TO, x))
            min(left, right) * smooth(0f, TOP_FADE, y)
        }
    }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            logo = runCatching { Res.readBytes("drawable/logo_mark.svg") }.getOrNull()
                ?.let { rasterise(it, 60, 40) }
                ?.let(::badgeIcon)
        }
    }

    fun show(announcement: DesktopTrackToast.Announcement, onOpen: () -> Unit): Unit = SwingUtilities.invokeLater {
        this.onOpen = onOpen
        corner = DesktopTrackCardSettings.corner.value
        val w = ensureWindow() ?: return@invokeLater
        val next = prepare(announcement)
        val up = w.isVisible && face != null && appearTo > 0f
        if (up) {
            // A new song while the card is up: it changes in place.
            oldFace = face
            barFromFraction = drawnFraction
            barFrom = drawnBar
            swapStart = System.nanoTime()
            face = next
        } else {
            face = next
            oldFace = null
            swapStart = 0L
            barFromFraction = 0f
            barFrom = next.bar
            paused = if (isPaused(next)) 1f else 0f
            w.location = location(corner)
            w.isVisible = true
            animateAppear(1f, IN_MS)
        }
        // Always above, even over a borderless game that took the top a moment ago.
        w.toFront()
        clockTimer.start()
        startFrames()
        stayTimer.stop()
        val stay = DesktopTrackCardSettings.duration.value
        if (stay != TrackCardDuration.ALWAYS) {
            stayTimer.initialDelay = stay.millis.toInt()
            stayTimer.start()
        }
        DesktopTrackLog.log("now playing card: shown on the desktop (${corner.name.lowercase()})")
    }

    /** Takes the card away; [release] also clears the announcement, when this card still owns it. */
    fun hide(release: Boolean = false): Unit = SwingUtilities.invokeLater {
        val leaving = face ?: return@invokeLater
        if (appearTo == 0f) return@invokeLater
        stayTimer.stop()
        animateAppear(0f, OUT_MS) {
            window?.isVisible = false
            clockTimer.stop()
            face = null
            oldFace = null
            if (release && DesktopTrackToast.announcement.value?.serial == leaving.announcement.serial) {
                DesktopTrackToast.dismiss()
            }
        }
    }

    private fun prepare(a: DesktopTrackToast.Announcement): Face {
        val full = a.art?.let { runCatching { it.toAwtImage() }.getOrNull() }
        val glass = full?.let { coverGlass(it) }
        // Shrunk smoothly once, to three times its size on the card: drawn straight from the
        // 480 px original, one bicubic step down to 61 px comes out jagged.
        val cover = full?.let { smoothScale(it, COVER_S * 3) }
        val bar = if (DesktopTrackCardSettings.style.value == TrackCardStyle.ALBUM) {
            a.accent?.let { pastel(Color(it.toArgb(), true)) } ?: GREY_BAR
        } else {
            CLASSIC_BAR
        }
        return Face(a, cover, glass, bar)
    }

    private fun isPaused(f: Face): Boolean {
        val now = DesktopTrackToast.playback?.value ?: return false
        return now.song?.videoId == f.announcement.song.videoId && !now.isPlaying
    }

    // ---- Timing ----------------------------------------------------------------------------

    private fun animateAppear(to: Float, ms: Float, then: (() -> Unit)? = null) {
        if (DesktopAppearanceSettings.reduceAnimation.value) {
            appear = to
            appearTo = to
            panel.repaint()
            then?.invoke()
            return
        }
        appearFrom = appear
        appearTo = to
        appearStart = System.nanoTime()
        appearMs = ms
        afterAppear = then
        startFrames()
    }

    private fun startFrames() {
        lastFrame = System.nanoTime()
        if (!frameTimer.isRunning) frameTimer.start()
    }

    /** One animation frame; stops the frame timer once nothing is moving. */
    private fun frame() {
        val now = System.nanoTime()
        val dt = (now - lastFrame) / 1_000_000f
        lastFrame = now
        var moving = false

        if (appear != appearTo) {
            val t = ((now - appearStart) / 1_000_000f / appearMs).coerceIn(0f, 1f)
            val eased = if (appearTo > appearFrom) 1f - (1f - t) * (1f - t) * (1f - t) else t * t
            appear = appearFrom + (appearTo - appearFrom) * eased
            if (t >= 1f) {
                appear = appearTo
                val then = afterAppear
                afterAppear = null
                then?.invoke()
            } else {
                moving = true
            }
        }

        if (swapStart != 0L) {
            if ((now - swapStart) / 1_000_000f >= SWAP_MS) {
                swapStart = 0L
                oldFace = null
            } else {
                moving = true
            }
        }

        val f = face
        val target = if (f != null && isPaused(f)) 1f else 0f
        if (paused != target) {
            val step = dt / PAUSE_FADE_MS
            paused = if (target > paused) min(target, paused + step) else max(target, paused - step)
            moving = moving || paused != target
        }

        panel.repaint()
        if (!moving) frameTimer.stop()
    }

    /** A few times a second while the card is up: the times and bar move, pause and play show. */
    private fun tick() {
        val f = face ?: return
        val now = DesktopTrackToast.playback?.value
        if (now != null && now.song == null) {
            hide(release = true)
            return
        }
        val target = if (isPaused(f)) 1f else 0f
        if (paused != target) startFrames()
        panel.repaint()
    }

    // ---- Window ----------------------------------------------------------------------------

    private fun ensureWindow(): JWindow? {
        window?.let { return it }
        return runCatching {
            JWindow().apply {
                type = java.awt.Window.Type.UTILITY // no taskbar button
                isAlwaysOnTop = true
                focusableWindowState = false
                val device: GraphicsDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                val perPixel = device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
                if (perPixel) {
                    background = Color(0, 0, 0, 0)
                    panel.isOpaque = false
                    setSize(W + PAD * 2, H + PAD * 2)
                    panel.inset = PAD
                } else {
                    // No see-through windows on this system: a plain card, cut to shape.
                    background = Color(20, 20, 20)
                    setSize(W, H)
                    panel.inset = 0
                    runCatching {
                        shape = RoundRectangle2D.Double(0.0, 0.0, W.toDouble(), H.toDouble(), RADIUS * 2.0, RADIUS * 2.0)
                    }
                }
                contentPane = panel
                DesktopTrackLog.log("now playing card: desktop window ready (see-through: $perPixel)")
            }.also { window = it }
        }.onFailure { DesktopTrackLog.log("now playing card: no desktop window (${it.message})") }.getOrNull()
    }

    /** Where the window goes so the card rests [EDGE_MARGIN] inside [corner], clear of the taskbar. */
    private fun location(corner: TrackCardCorner): Point {
        val config = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        val left = bounds.x + insets.left + EDGE_MARGIN
        val right = bounds.x + bounds.width - insets.right - EDGE_MARGIN - W
        val top = bounds.y + insets.top + EDGE_MARGIN
        val bottom = bounds.y + bounds.height - insets.bottom - EDGE_MARGIN - H
        val x = when (corner.horizontal) {
            -1 -> left
            1 -> right
            else -> (left + right) / 2
        }
        val y = if (corner.top) top else bottom
        return Point(x - panel.inset, y - panel.inset)
    }

    // ---- Drawing ---------------------------------------------------------------------------

    private class CardPanel : JPanel(null) {
        var inset = PAD

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // Mid-game, a stray click shouldn't pull BitChord over the game.
                    if (!DesktopFullscreenApps.active()) onOpen()
                    hide(release = true)
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                // Clear to see-through: the window has no background of its own to paint.
                g2.composite = AlphaComposite.Clear
                g2.fillRect(0, 0, width, height)
                g2.composite = AlphaComposite.SrcOver
                val current = face ?: return
                if (appear <= 0f) return
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // Greyscale: subpixel (ClearType-style) text leaves coloured fringes on a see-through window.
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                val away = (1f - appear) * SLIDE
                val dx = if (inset > 0) away * corner.horizontal else 0f
                val dy = if (inset > 0 && corner.horizontal == 0) away * (if (corner.top) -1f else 1f) else 0f
                g2.translate(inset + dx.toDouble(), inset + dy.toDouble())
                // Drawn off screen at the screen's own pixel density, then cut to the card's
                // rounded shape with an antialiased mask: a clip in Java2D is never antialiased,
                // and left the corners stepped.
                val scale = g2.transform.scaleX.coerceAtLeast(1.0)
                val buffer = cardBuffer(scale)
                val bg = buffer.createGraphics()
                bg.composite = AlphaComposite.Clear
                bg.fillRect(0, 0, buffer.width, buffer.height)
                bg.composite = AlphaComposite.SrcOver
                bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                bg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                bg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                bg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                bg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                bg.scale(scale, scale)
                drawCard(bg, current, 1f)
                bg.transform = java.awt.geom.AffineTransform()
                bg.composite = AlphaComposite.DstIn
                bg.drawImage(cardMask(buffer.width, buffer.height, scale), 0, 0, null)
                bg.dispose()
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, appear.coerceIn(0f, 1f))
                g2.drawImage(buffer, 0, 0, W, H, null)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun drawCard(g: Graphics2D, current: Face, alpha: Float) {
        val swapMs = if (swapStart == 0L) Float.MAX_VALUE else (System.nanoTime() - swapStart) / 1_000_000f
        val old = oldFace.takeIf { swapStart != 0L }
        // The tint over whatever is behind, which shows through at the card's ends.
        fade(g, alpha) {
            g.color = TINT
            g.fillRect(0, 0, W, H)
        }
        // The ground: the cover's, cross-fading to the new song's.
        val coverIn = ease((swapMs / COVER_FADE_MS).coerceIn(0f, 1f))
        if (old != null) drawGlass(g, old, alpha)
        drawGlass(g, current, alpha * (if (old != null) coverIn else 1f))

        // The cover, with the app's mark and name under it.
        if (old != null) drawCover(g, old, alpha)
        drawCover(g, current, alpha * (if (old != null) coverIn else 1f))
        // Paused: the cover dims almost to black under a white pause glyph.
        if (paused > 0f) {
            val coverClip = RoundRectangle2D.Float(COVER_X.toFloat(), COVER_Y.toFloat(), COVER_S.toFloat(), COVER_S.toFloat(), 5f, 5f)
            g.color = Color(0f, 0f, 0f, (0.86f * paused * alpha).coerceIn(0f, 1f))
            g.fill(coverClip)
            val barW = 6f
            val gap = 7f
            val barH = 22f
            val cx = COVER_X + COVER_S / 2f
            val cy = COVER_Y + COVER_S / 2f
            g.color = Color(1f, 1f, 1f, (paused * alpha).coerceIn(0f, 1f))
            g.fill(Rectangle2D.Float(cx - gap / 2 - barW, cy - barH / 2, barW, barH))
            g.fill(Rectangle2D.Float(cx + gap / 2, cy - barH / 2, barW, barH))
        }
        drawMark(g, alpha * (1f - 0.72f * paused))

        // Title and artist: the old words fade out, then the new ones fade in.
        if (old != null && swapMs < TEXT_OUT_MS) {
            drawWords(g, old.announcement, alpha * (1f - swapMs / TEXT_OUT_MS))
        }
        val wordsIn = if (old == null) 1f else ((swapMs - TEXT_IN_DELAY_MS) / TEXT_IN_MS).coerceIn(0f, 1f)
        if (wordsIn > 0f) drawWords(g, current.announcement, alpha * ease(wordsIn))

        // The progress row: new times at once; the bar glides to its new place and colour.
        val (position, duration) = progress(current.announcement)
        val target = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val glide = if (old == null) 1f else ease((swapMs / BAR_GLIDE_MS).coerceIn(0f, 1f))
        val fraction = barFromFraction + (target - barFromFraction) * glide
        val barColour = mix(barFrom, current.bar, glide)
        drawnFraction = fraction
        drawnBar = barColour
        drawProgress(g, position, duration, fraction, barColour, alpha)
    }

    private var buffer: BufferedImage? = null
    private var mask: BufferedImage? = null

    /** The off-screen card, reused while the screen's density stays the same. */
    private fun cardBuffer(scale: Double): BufferedImage {
        val w = kotlin.math.ceil(W * scale).toInt()
        val h = kotlin.math.ceil(H * scale).toInt()
        buffer?.takeIf { it.width == w && it.height == h }?.let { return it }
        return BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE).also { buffer = it }
    }

    /** The card's rounded shape, antialiased, as an alpha mask the size of [cardBuffer]. */
    private fun cardMask(w: Int, h: Int, scale: Double): BufferedImage {
        mask?.takeIf { it.width == w && it.height == h }?.let { return it }
        val m = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = m.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.scale(scale, scale)
        g.color = Color.WHITE
        g.fill(RoundRectangle2D.Float(0f, 0f, W.toFloat(), H.toFloat(), RADIUS * 2, RADIUS * 2))
        g.dispose()
        return m.also { mask = it }
    }

    private fun drawGlass(g: Graphics2D, f: Face, alpha: Float) {
        val glass = f.glass ?: plainGlass
        fade(g, alpha) { g.drawImage(glass, 0, 0, W, H, null) }
    }

    /** No cover: a plain dark ground, faded at both ends the same way. */
    private val plainGlass: BufferedImage by lazy {
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val fade = groundAlpha
        for (y in 0 until H) {
            for (x in 0 until W) {
                val a = (fade[y * W + x] * 255).toInt().coerceIn(0, 255)
                img.setRGB(x, y, (a shl 24) or 0x141414)
            }
        }
        img
    }

    private fun drawCover(g: Graphics2D, f: Face, alpha: Float) {
        if (alpha <= 0f) return
        val coverClip = RoundRectangle2D.Float(COVER_X.toFloat(), COVER_Y.toFloat(), COVER_S.toFloat(), COVER_S.toFloat(), 5f, 5f)
        val image = f.cover
        fade(g, alpha) {
            // Filled with the cover as a texture rather than clipped to it, so the rounded corners
            // are antialiased.
            g.paint = if (image != null) {
                java.awt.TexturePaint(
                    image,
                    Rectangle2D.Float(COVER_X.toFloat(), COVER_Y.toFloat(), COVER_S.toFloat(), COVER_S.toFloat()),
                )
            } else {
                Color(40, 42, 50)
            }
            g.fill(coverClip)
        }
    }

    private fun drawMark(g: Graphics2D, alpha: Float) {
        g.font = Font("Segoe UI", Font.BOLD, 11)
        val fm = g.fontMetrics
        val name = "BitChord"
        val iconS = 12
        val rowW = iconS + 4 + fm.stringWidth(name)
        val rowX = COVER_X + (COVER_S - rowW) / 2
        val centreY = COVER_Y + COVER_S + 9
        fade(g, alpha) {
            val icon = logo
            if (icon != null) {
                g.drawImage(icon, rowX, centreY - iconS / 2, iconS, iconS, null)
            } else {
                g.color = Color.WHITE
                g.fillOval(rowX, centreY - iconS / 2, iconS, iconS)
            }
            g.color = Color.WHITE
            g.drawString(name, rowX + iconS + 4, centreY + (fm.ascent - fm.descent) / 2)
        }
    }

    private fun drawWords(g: Graphics2D, a: DesktopTrackToast.Announcement, alpha: Float) {
        if (alpha <= 0f) return
        val width = W - RIGHT_PAD - TEXT_X
        fade(g, alpha) {
            g.font = Font("Segoe UI", Font.BOLD, 17)
            g.color = Color.WHITE
            g.drawString(fit(a.song.title, g.fontMetrics, width), TEXT_X, 34)
            g.font = Font("Segoe UI", Font.PLAIN, 14).deriveFont(14.5f)
            g.color = Color(0x91, 0x91, 0x91)
            g.drawString(fit(a.song.artist, g.fontMetrics, width), TEXT_X, 56)
        }
    }

    private fun drawProgress(g: Graphics2D, position: Long, duration: Long, fraction: Float, bar: Color, alpha: Float) {
        g.font = Font("Segoe UI", Font.PLAIN, 13)
        val fm = g.fontMetrics
        val elapsed = clock(position)
        val total = if (duration > 0L) clock(duration) else "–:––"
        val rowY = 73f
        val baseline = rowY + (fm.ascent - fm.descent) / 2f
        val right = W - RIGHT_PAD
        val totalW = fm.stringWidth(total)
        val barX = TEXT_X + fm.stringWidth(elapsed) + 10f
        val barW = (right - totalW - 10f) - barX
        fade(g, alpha) {
            g.color = Color(0x8C, 0x8C, 0x8C)
            g.drawString(elapsed, TEXT_X.toFloat(), baseline)
            g.drawString(total, (right - totalW).toFloat(), baseline)
            if (barW > 10f) {
                val h = 6f
                // A faint white over the ground, so it takes the cover's tint.
                g.color = Color(255, 255, 255, 46)
                g.fill(RoundRectangle2D.Float(barX, rowY - h / 2, barW, h, h, h))
                if (fraction > 0f) {
                    g.color = bar
                    g.fill(RoundRectangle2D.Float(barX, rowY - h / 2, max(h, barW * fraction), h, h, h))
                }
            }
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    /** Draws [block] at [alpha] over what's there. */
    private inline fun fade(g: Graphics2D, alpha: Float, block: () -> Unit) {
        val before = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        block()
        g.composite = before
    }

    private fun ease(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mix(a: Color, b: Color, t: Float): Color = Color(
        (a.red + (b.red - a.red) * t).toInt().coerceIn(0, 255),
        (a.green + (b.green - a.green) * t).toInt().coerceIn(0, 255),
        (a.blue + (b.blue - a.blue) * t).toInt().coerceIn(0, 255),
    )

    /**
     * The cover's colour softened as the reference softens it: peach from orange, sky from blue,
     * mint from teal — light, and only a little saturated.
     */
    private fun pastel(c: Color): Color {
        val hsb = Color.RGBtoHSB(c.red, c.green, c.blue, null)
        return Color(Color.HSBtoRGB(hsb[0], min(0.38f, hsb[1]), 0.9f))
    }

    /** Where the song is, dead reckoned between the engine's readings. */
    private fun progress(a: DesktopTrackToast.Announcement): Pair<Long, Long> {
        val now = DesktopTrackToast.playback?.value?.takeIf { it.song?.videoId == a.song.videoId } ?: return 0L to 0L
        val running = now.isPlaying && !now.awaitingAudio && now.positionSampledAtNanos > 0L
        val drift = if (running) (System.nanoTime() - now.positionSampledAtNanos) / 1_000_000L else 0L
        val position = (now.positionMs + drift).coerceIn(0L, now.durationMs.coerceAtLeast(0L))
        return position to now.durationMs
    }

    private fun fit(text: String, fm: FontMetrics, width: Int): String {
        if (fm.stringWidth(text) <= width) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && fm.stringWidth(text.substring(0, end) + ellipsis) > width) end--
        return text.substring(0, end).trimEnd() + ellipsis
    }

    private fun clock(ms: Long): String {
        val total = (ms / 1_000L).coerceAtLeast(0L)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    /**
     * The card's ground for this song, fitted to the reference: the cover at nearly the card's
     * width, centred, its middle just below the card's top edge; blurred with a Gaussian light
     * enough to keep the cover's broad shapes; darkened to under a quarter; faded at both ends.
     */
    private fun coverGlass(cover: Image): BufferedImage? = runCatching {
        val iw = cover.getWidth(null)
        val ih = cover.getHeight(null)
        if (iw <= 0 || ih <= 0) return@runCatching null
        // Worked at full size: the blur is light enough that half size would soften it.
        val hw = W
        val hh = H
        val sigma = GROUND_BLUR
        val m = kotlin.math.ceil(sigma * 3).toInt()
        val cw = hw + 2 * m
        val ch = hh + 2 * m
        val canvas = BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val side = (W * GROUND_COVER_WIDTH).toInt()
        val small = smoothScale(cover, side)
        // Around the cover, its own average colour, so the blur doesn't pull in black.
        g.color = averageColour(small)
        g.fillRect(0, 0, cw, ch)
        g.drawImage(small, m + (hw - side) / 2, m + (hh * GROUND_COVER_CENTRE_Y).toInt() - side / 2, side, side, null)
        g.dispose()
        val blurred = gaussian(canvas, sigma)
        val big = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val up = big.createGraphics()
        up.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        up.drawImage(blurred, 0, 0, W, H, m, m, m + hw, m + hh, null)
        up.dispose()
        val out = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val fade = groundAlpha
        for (y in 0 until H) {
            for (x in 0 until W) {
                val c = big.getRGB(x, y)
                val cr = ((c shr 16) and 0xFF).toFloat()
                val cg = ((c shr 8) and 0xFF).toFloat()
                val cb = (c and 0xFF).toFloat()
                val l = 0.299f * cr + 0.587f * cg + 0.114f * cb
                fun tone(v: Float) = (GROUND_BRIGHTNESS * (l + GROUND_SATURATION * (v - l))).toInt().coerceIn(0, 255)
                val r = tone(cr)
                val gg = tone(cg)
                val b = tone(cb)
                val a = (fade[y * W + x] * 255).toInt().coerceIn(0, 255)
                out.setRGB(x, y, (a shl 24) or (r shl 16) or (gg shl 8) or b)
            }
        }
        out
    }.getOrNull()

    /**
     * [image]'s middle square at [size]×[size], halved step by step so nothing is skipped on the
     * way down.
     */
    private fun smoothScale(image: Image, size: Int): BufferedImage {
        val iw = image.getWidth(null)
        val ih = image.getHeight(null)
        val side = min(iw, ih)
        val square = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val sg = square.createGraphics()
        sg.drawImage(image, 0, 0, side, side, (iw - side) / 2, (ih - side) / 2, (iw - side) / 2 + side, (ih - side) / 2 + side, null)
        sg.dispose()
        var w = side
        var h = side
        var current: Image = square
        while (w / 2 >= size && h / 2 >= size) {
            w /= 2
            h /= 2
            current = scaled(current, w, h)
        }
        return scaled(current, size, size)
    }

    private fun scaled(image: Image, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(image, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun averageColour(image: BufferedImage): Color {
        var r = 0L
        var g = 0L
        var b = 0L
        val n = image.width.toLong() * image.height
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val c = image.getRGB(x, y)
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
        }
        return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** A true Gaussian blur, done as two one-dimensional passes: smooth, with no blocks or bands. */
    private fun gaussian(src: BufferedImage, sigma: Float): BufferedImage {
        val w = src.width
        val h = src.height
        val radius = kotlin.math.ceil(sigma * 3).toInt()
        val kernel = FloatArray(radius * 2 + 1) { i ->
            val d = (i - radius).toFloat()
            kotlin.math.exp(-(d * d) / (2 * sigma * sigma))
        }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        val pixels = src.getRGB(0, 0, w, h, null, 0, w)
        val r = FloatArray(w * h) { ((pixels[it] shr 16) and 0xFF).toFloat() }
        val g = FloatArray(w * h) { ((pixels[it] shr 8) and 0xFF).toFloat() }
        val b = FloatArray(w * h) { (pixels[it] and 0xFF).toFloat() }
        fun pass(channel: FloatArray, horizontal: Boolean): FloatArray {
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    for (k in kernel.indices) {
                        val o = k - radius
                        val xx = if (horizontal) (x + o).coerceIn(0, w - 1) else x
                        val yy = if (horizontal) y else (y + o).coerceIn(0, h - 1)
                        sum += channel[yy * w + xx] * kernel[k]
                    }
                    out[y * w + x] = sum
                }
            }
            return out
        }
        val rr = pass(pass(r, true), false)
        val gg = pass(pass(g, true), false)
        val bb = pass(pass(b, true), false)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val result = IntArray(w * h) { i ->
            (rr[i].toInt().coerceIn(0, 255) shl 16) or (gg[i].toInt().coerceIn(0, 255) shl 8) or bb[i].toInt().coerceIn(0, 255)
        }
        out.setRGB(0, 0, w, h, result, 0, w)
        return out
    }

    /** The app's mark cut out of a white disc. */
    private fun badgeIcon(mark: BufferedImage): BufferedImage {
        val size = 48
        val icon = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = icon.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.color = Color.WHITE
        g.fillOval(0, 0, size, size)
        g.composite = AlphaComposite.DstOut
        val markW = (size * 0.62).toInt()
        val markH = markW * mark.height / mark.width
        g.drawImage(mark, (size - markW) / 2, (size - markH) / 2 + 1, markW, markH, null)
        g.dispose()
        return icon
    }

    /** The app's mark, drawn from its SVG at [w]×[h]. */
    private fun rasterise(svg: ByteArray, w: Int, h: Int): BufferedImage? = runCatching {
        val dom = SVGDOM(Data.makeFromBytes(svg))
        dom.setContainerSize(w.toFloat(), h.toFloat())
        val surface = Surface.makeRasterN32Premul(w, h)
        val intrinsicW = dom.root?.width?.value?.takeIf { it > 0f } ?: w.toFloat()
        val intrinsicH = dom.root?.height?.value?.takeIf { it > 0f } ?: h.toFloat()
        surface.canvas.scale(w / intrinsicW, h / intrinsicH)
        dom.render(surface.canvas)
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
        if (!surface.readPixels(bitmap, 0, 0)) return@runCatching null
        val bytes = bitmap.readPixels() ?: return@runCatching null
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val at = (y * w + x) * 4
                val b = bytes[at].toInt() and 0xFF
                val g = bytes[at + 1].toInt() and 0xFF
                val r = bytes[at + 2].toInt() and 0xFF
                val alpha = bytes[at + 3].toInt() and 0xFF
                image.setRGB(x, y, (alpha shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        image
    }.getOrNull()
}
