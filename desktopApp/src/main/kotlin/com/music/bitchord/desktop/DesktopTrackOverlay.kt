package com.music.bitchord.desktop

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import bitchord.desktopapp.generated.resources.Res
import java.awt.AlphaComposite
import java.awt.BasicStroke
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
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM

/**
 * The "Now playing" card on the desktop: a small see-through card, laid out like the Spotify card
 * in Discord's overlay — the cover on a lighter tile with the app's mark under it, the title, the
 * artist, and a progress bar between the elapsed and total time.
 *
 * Drawn with plain Java2D in a per-pixel translucent Swing window rather than with Compose. A
 * see-through Compose window needs the GPU renderer to draw into a transparent surface, which
 * fails on some machines and every non-default renderer — the card then simply never appeared.
 * Java2D's translucent windows go through Windows' own layered-window path, on any renderer, in
 * safe mode too, and cost the GPU nothing while a game runs. Everything here runs on the AWT
 * event thread.
 */
internal object DesktopTrackOverlay {

    private const val W = 340
    private const val H = 90

    /** Transparent room around the card for the slide in from the edge; clicks pass through it. */
    private const val PAD = 28
    private const val EDGE_MARGIN = 16
    private const val SLIDE = 22f
    private const val IN_MS = 240
    private const val OUT_MS = 180

    private var window: JWindow? = null
    private val panel = CardPanel()

    private var shown: DesktopTrackToast.Announcement? = null
    private var cover: Image? = null

    /** What was on screen behind the card when it came in, blurred — the card's frosted glass. */
    private var backdrop: BufferedImage? = null
    private var corner: TrackCardCorner = TrackCardCorner.BOTTOM_RIGHT
    private var onOpen: () -> Unit = {}

    /** 0 = gone, 1 = resting. */
    private var appear = 0f
    private var animFrom = 0f
    private var animTo = 0f
    private var animStart = 0L
    private var animMs = 1
    private var afterAnim: (() -> Unit)? = null
    private val animTimer: Timer = Timer(15) { stepAnimation() }
    private val stayTimer: Timer = Timer(5_000) { hide(release = true) }.apply { isRepeats = false }
    private val progressTimer: Timer = Timer(250) { panel.repaint() }

    @Volatile private var logo: BufferedImage? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            logo = runCatching { Res.readBytes("drawable/logo_mark.svg") }.getOrNull()
                ?.let { rasterise(it, 60, 40) }
                ?.let(::badgeIcon)
        }
    }

    fun show(announcement: DesktopTrackToast.Announcement, onOpen: () -> Unit): Unit = SwingUtilities.invokeLater {
        this.onOpen = onOpen
        val replacing = shown != null && appear > 0f
        shown = announcement
        cover = announcement.art?.let { runCatching { it.toAwtImage() }.getOrNull() }
        corner = DesktopTrackCardSettings.corner.value
        val w = ensureWindow() ?: return@invokeLater
        val at = location(corner)
        w.location = at
        if (!w.isVisible) {
            // Read before the card is up, so the glass shows what's behind it rather than itself.
            backdrop = blurredScreen(java.awt.Rectangle(at.x + panel.inset, at.y + panel.inset, W, H))
            w.isVisible = true
        }
        // Always above, even over a borderless game that took the top a moment ago.
        w.toFront()
        progressTimer.start()
        if (!replacing) animate(1f, IN_MS)
        panel.repaint()
        stayTimer.stop()
        stayTimer.initialDelay = DesktopTrackCardSettings.duration.value.millis.toInt()
        stayTimer.start()
        DesktopTrackLog.log("now playing card: shown on the desktop (${corner.name.lowercase()})")
    }

    /** Takes the card away; [release] also clears the announcement, when this card still owns it. */
    fun hide(release: Boolean = false): Unit = SwingUtilities.invokeLater {
        val leaving = shown ?: return@invokeLater
        stayTimer.stop()
        animate(0f, OUT_MS) {
            window?.isVisible = false
            progressTimer.stop()
            shown = null
            cover = null
            backdrop = null
            if (release && DesktopTrackToast.announcement.value?.serial == leaving.serial) DesktopTrackToast.dismiss()
        }
    }

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
                    background = Color(26, 32, 44)
                    setSize(W, H)
                    panel.inset = 0
                    runCatching { shape = RoundRectangle2D.Double(0.0, 0.0, W.toDouble(), H.toDouble(), 24.0, 24.0) }
                }
                contentPane = panel
                DesktopTrackLog.log("now playing card: desktop window ready (see-through: $perPixel)")
            }.also { window = it }
        }.onFailure { DesktopTrackLog.log("now playing card: no desktop window (${it.message})") }.getOrNull()
    }

    private fun animate(to: Float, ms: Int, then: (() -> Unit)? = null) {
        if (DesktopAppearanceSettings.reduceAnimation.value) {
            appear = to
            panel.repaint()
            then?.invoke()
            return
        }
        animFrom = appear
        animTo = to
        animStart = System.nanoTime()
        animMs = ms
        afterAnim = then
        animTimer.start()
    }

    private fun stepAnimation(): Unit {
        val t = ((System.nanoTime() - animStart) / 1_000_000f / animMs).coerceIn(0f, 1f)
        // Ease out on the way in, ease in on the way out.
        val eased = if (animTo > animFrom) 1f - (1f - t) * (1f - t) * (1f - t) else t * t
        appear = animFrom + (animTo - animFrom) * eased
        panel.repaint()
        if (t >= 1f) {
            animTimer.stop()
            val then = afterAnim
            afterAnim = null
            then?.invoke()
        }
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
                val announcement = shown ?: return
                if (appear <= 0f) return
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, appear.coerceIn(0f, 1f))
                val away = (1f - appear) * SLIDE
                val dx = if (inset > 0) away * corner.horizontal else 0f
                val dy = if (inset > 0 && corner.horizontal == 0) away * (if (corner.top) -1f else 1f) else 0f
                g2.translate(inset + dx.toDouble(), inset + dy.toDouble())
                drawCard(g2, announcement)
            } finally {
                g2.dispose()
            }
        }

        private fun drawCard(g: Graphics2D, a: DesktopTrackToast.Announcement) {
            // The glass: what's behind, heavily blurred, under a dark wash.
            val card = RoundRectangle2D.Float(0f, 0f, W.toFloat(), H.toFloat(), 24f, 24f)
            val outer = g.clip
            g.clip(card)
            val glass = backdrop
            if (glass != null) {
                g.drawImage(glass, 0, 0, W, H, null)
                g.color = Color(0, 0, 0, 112)
            } else {
                g.color = Color(26, 32, 44, 236)
            }
            g.fill(card)

            // The cover, square, with the app's mark and name centred under it.
            val coverX = 21
            val coverY = 9
            val coverS = 55
            val coverClip = RoundRectangle2D.Float(coverX.toFloat(), coverY.toFloat(), coverS.toFloat(), coverS.toFloat(), 6f, 6f)
            g.clip(coverClip)
            val image = cover
            val iw = image?.getWidth(null) ?: -1
            val ih = image?.getHeight(null) ?: -1
            if (image != null && iw > 0 && ih > 0) {
                val side = minOf(iw, ih)
                val sx = (iw - side) / 2
                val sy = (ih - side) / 2
                g.drawImage(image, coverX, coverY, coverX + coverS, coverY + coverS, sx, sy, sx + side, sy + side, null)
            } else {
                g.color = Color(58, 62, 72)
                g.fill(coverClip)
            }
            g.clip = outer
            g.clip(card)

            g.font = Font("Segoe UI", Font.BOLD, 11)
            val name = "BitChord"
            val nameW = g.fontMetrics.stringWidth(name)
            val iconS = 13
            val rowW = iconS + 4 + nameW
            val rowX = coverX + (coverS - rowW) / 2
            val iconY = 69
            val icon = logo
            if (icon != null) {
                g.drawImage(icon, rowX, iconY, iconS, iconS, null)
            } else {
                g.color = Color.WHITE
                g.fillOval(rowX, iconY, iconS, iconS)
            }
            g.color = Color.WHITE
            g.drawString(name, rowX + iconS + 4, 80)

            // Title, artist, and the progress row.
            val textX = 105
            val right = W - 10
            g.font = Font("Segoe UI", Font.BOLD, 15)
            g.color = Color.WHITE
            g.drawString(fit(a.song.title, g.fontMetrics, right - textX), textX, 33)
            g.font = Font("Segoe UI", Font.PLAIN, 12).deriveFont(12.5f)
            g.color = Color(163, 163, 166)
            g.drawString(fit(a.song.artist, g.fontMetrics, right - textX), textX, 51)

            val (position, duration) = progress(a)
            g.font = Font("Segoe UI", Font.PLAIN, 11)
            val tfm = g.fontMetrics
            val elapsed = clock(position)
            val total = if (duration > 0L) clock(duration) else "–:––"
            val rowY = 70
            val baseline = rowY + (tfm.ascent - tfm.descent) / 2
            g.color = Color(158, 158, 161)
            g.drawString(elapsed, textX, baseline)
            val totalW = tfm.stringWidth(total)
            g.drawString(total, right - totalW, baseline)
            val barX = textX + tfm.stringWidth(elapsed) + 8
            val barW = (right - totalW - 8) - barX
            if (barW > 10) {
                g.color = Color(255, 255, 255, 38)
                g.fill(RoundRectangle2D.Float(barX.toFloat(), rowY - 2.5f, barW.toFloat(), 5f, 5f, 5f))
                val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                if (fraction > 0f) {
                    g.color = barColour(a)
                    g.fill(RoundRectangle2D.Float(barX.toFloat(), rowY - 2.5f, maxOf(5f, barW * fraction), 5f, 5f, 5f))
                }
            }
            g.clip = outer
        }

        private fun barColour(a: DesktopTrackToast.Announcement): Color {
            val accent = a.accent
            return if (DesktopTrackCardSettings.style.value == TrackCardStyle.ALBUM && accent != null) {
                Color(accent.toArgb(), true)
            } else {
                Color(160, 178, 209)
            }
        }
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
     * The screen under [area], blurred hard enough that only its colours are left, for the card's
     * glass. Read once as the card comes in. Null where the screen can't be read.
     */
    private fun blurredScreen(area: java.awt.Rectangle): BufferedImage? = runCatching {
        val grab = java.awt.Robot().createScreenCapture(area)
        // Down to a sixth, blurred there, and back up smoothly: a wide, soft blur for very little work.
        val sw = maxOf(1, area.width / 6)
        val sh = maxOf(1, area.height / 6)
        var small = BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB)
        val down = small.createGraphics()
        down.drawImage(grab.getScaledInstance(sw, sh, Image.SCALE_AREA_AVERAGING), 0, 0, null)
        down.dispose()
        repeat(3) { small = boxBlur(small, 2) }
        val out = BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB)
        val up = out.createGraphics()
        up.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        up.drawImage(small, 0, 0, area.width, area.height, null)
        up.dispose()
        out
    }.onFailure { DesktopTrackLog.log("now playing card: couldn't read the screen for the glass (${it.message})") }.getOrNull()

    private fun boxBlur(src: BufferedImage, r: Int): BufferedImage {
        val w = src.width
        val h = src.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rs = 0
                var gs = 0
                var bs = 0
                var n = 0
                for (dy in -r..r) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -r..r) {
                        val c = src.getRGB((x + dx).coerceIn(0, w - 1), yy)
                        rs += (c shr 16) and 0xFF
                        gs += (c shr 8) and 0xFF
                        bs += c and 0xFF
                        n++
                    }
                }
                out.setRGB(x, y, ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n))
            }
        }
        return out
    }

    /** The app's mark cut out of a white disc. */
    private fun badgeIcon(mark: BufferedImage): BufferedImage {
        val size = 52
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

    /** The app's mark, drawn from its SVG at [w]×[h] (twice the size it shows at, for sharp edges). */
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
