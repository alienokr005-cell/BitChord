package com.music.bitchord.desktop

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.PrintWriter
import java.io.StringWriter
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which graphics backend draws the window. Read once at launch; a change needs a restart. */
internal enum class DesktopRenderer(val label: String, val skikoApi: String?) {
    AUTOMATIC("Automatic", null),
    OPENGL("OpenGL", "OPENGL"),
    SOFTWARE("Compatibility", "SOFTWARE_FAST"),
}

/**
 * What keeps the window from going blank, and what records it when it does.
 *
 * A blank window is the drawing stopping while the app goes on underneath: the GPU device lost
 * across sleep, a driver reset or a monitor change; the heap running out mid-frame; or a crash in
 * the UI. Each has its own answer here — a redraw when the window comes back, a full stack trace
 * in `desktop.log` instead of nothing, the window rebuilt after a UI crash instead of left closed,
 * and a renderer the listener can switch to when the GPU path keeps failing on their machine.
 */
internal object DesktopStability {

    private const val KEY_RENDERER = "renderer"
    private const val KEY_LAUNCH_PENDING = "launch_pending"

    /** How long a launch has to stay up before it counts as a good one. */
    private const val HEALTHY_AFTER_MS = 20_000L

    private val _renderer = MutableStateFlow(load())
    /** The renderer that will be used from the next launch. */
    val renderer: StateFlow<DesktopRenderer> = _renderer

    /** The renderer this launch is actually using. */
    var activeRenderer: DesktopRenderer = _renderer.value
        private set

    /**
     * Whether this launch is the safe one after a launch that didn't last — blank, crashed or closed
     * within moments of opening. Safe mode uses the automatic renderer and an opaque window, the
     * combination least likely to go blank, so Settings can be reached to change things.
     */
    var safeMode: Boolean = false
        private set

    /**
     * Whether the main window may be created see-through (for Mica/Acrylic) and the desktop card
     * drawn in a see-through window. Only on the automatic renderer — Skia's OpenGL path can't draw
     * into a transparent window on Windows, which shows as a blank one — and never in safe mode.
     */
    val transparencyAllowed: Boolean get() = !safeMode && activeRenderer == DesktopRenderer.AUTOMATIC

    private fun load(): DesktopRenderer {
        val saved = DesktopPersistence().string(KEY_RENDERER, DesktopRenderer.AUTOMATIC.name)
        return DesktopRenderer.entries.firstOrNull { it.name == saved } ?: DesktopRenderer.AUTOMATIC
    }

    fun setRenderer(value: DesktopRenderer) {
        DesktopPersistence().saveString(KEY_RENDERER, value.name)
        _renderer.value = value
    }

    /** First thing in `main`, before any window or the graphics library loads. */
    fun installEarly() {
        val persistence = DesktopPersistence()
        // The last launch never got as far as [markHealthy]: start safe, back on the automatic renderer.
        if (persistence.boolean(KEY_LAUNCH_PENDING, false)) {
            safeMode = true
            if (activeRenderer != DesktopRenderer.AUTOMATIC) setRenderer(DesktopRenderer.AUTOMATIC)
            activeRenderer = DesktopRenderer.AUTOMATIC
            DesktopTrackLog.log("last launch didn't last; starting in safe mode")
        }
        persistence.saveBoolean(KEY_LAUNCH_PENDING, true)
        runCatching { persistence.preferences.flush() }
        activeRenderer.skikoApi?.let { System.setProperty("skiko.renderApi", it) }
        DesktopTrackLog.log("renderer: ${activeRenderer.name}, transparent window allowed: $transparencyAllowed")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DesktopTrackLog.log("uncaught on ${thread.name}: ${stackTrace(error)}")
            if (error is OutOfMemoryError) {
                val runtime = Runtime.getRuntime()
                DesktopTrackLog.log(
                    "heap at OOM: used ${(runtime.totalMemory() - runtime.freeMemory()) shr 20} MB " +
                        "of ${runtime.maxMemory() shr 20} MB",
                )
                // Hand back what can be let go so the next frame can draw.
                runCatching { DesktopArtworkCache.clear() }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Redraws the window whenever it comes back — restored from the taskbar or moved to another
     * monitor — which is when a lost GPU surface shows up as a blank window.
     */
    fun watch(window: Window) {
        fun nudge() = SwingUtilities.invokeLater {
            window.revalidate()
            window.repaint()
        }
        window.addWindowListener(object : WindowAdapter() {
            override fun windowDeiconified(e: WindowEvent) = nudge()
        })
        window.addPropertyChangeListener("graphicsConfiguration") {
            DesktopTrackLog.log("window moved to another display; redrawing")
            nudge()
        }
    }

    /** From the main window, once it is up: counts this launch as a good one after a while. */
    suspend fun markHealthy() {
        kotlinx.coroutines.delay(HEALTHY_AFTER_MS)
        val persistence = DesktopPersistence()
        persistence.saveBoolean(KEY_LAUNCH_PENDING, false)
        runCatching { persistence.preferences.flush() }
        DesktopTrackLog.log("launch healthy")
    }

    /** Bumped to rebuild the main window after a crash in it. */
    val windowGeneration = mutableIntStateOf(0)

    private val recentCrashes = ArrayDeque<Long>()

    /**
     * On a crash in the window's UI: log it, then rebuild the window rather than close it — up to
     * three times a minute, past which the crash is real and repeating, and Compose's own error
     * dialog shows it.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    val exceptionHandlerFactory = WindowExceptionHandlerFactory { window ->
        WindowExceptionHandler { error ->
            DesktopTrackLog.log("window crashed: ${stackTrace(error)}")
            val now = System.currentTimeMillis()
            recentCrashes.addLast(now)
            while (recentCrashes.isNotEmpty() && now - recentCrashes.first() > 60_000) recentCrashes.removeFirst()
            if (recentCrashes.size > 3) {
                androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
                    .exceptionHandler(window)
                    .onException(error)
            } else {
                SwingUtilities.invokeLater { windowGeneration.intValue++ }
            }
        }
    }

    private fun stackTrace(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
}
