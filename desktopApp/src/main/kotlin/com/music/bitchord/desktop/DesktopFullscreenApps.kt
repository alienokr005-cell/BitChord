package com.music.bitchord.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference

/**
 * Whether a full-screen app — a game, a video, a presentation — has the screen right now.
 *
 * Asks Windows the same question it asks itself before showing its own notifications
 * (`SHQueryUserNotificationState`), which covers exclusive full-screen Direct3D games as well as
 * borderless-windowed ones. The desktop card stays away while it says yes: an always-on-top window
 * popping over a game costs frames, and over exclusive full screen can knock the game out of it.
 */
internal object DesktopFullscreenApps {

    private interface Shell32 : Library {
        fun SHQueryUserNotificationState(state: IntByReference): Int
    }

    private val shell32: Shell32? by lazy {
        if (!DesktopPlatform.isWindows) null
        else runCatching { Native.load("shell32", Shell32::class.java) }.getOrNull()
    }

    // QUERY_USER_NOTIFICATION_STATE values that mean "don't interrupt".
    private const val QUNS_BUSY = 2
    private const val QUNS_RUNNING_D3D_FULL_SCREEN = 3
    private const val QUNS_PRESENTATION_MODE = 4

    fun active(): Boolean {
        val api = shell32 ?: return false
        return runCatching {
            val state = IntByReference()
            api.SHQueryUserNotificationState(state) == 0 &&
                state.value in setOf(QUNS_BUSY, QUNS_RUNNING_D3D_FULL_SCREEN, QUNS_PRESENTATION_MODE)
        }.getOrDefault(false)
    }
}
