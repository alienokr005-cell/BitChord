package com.music.bitchord.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference

/**
 * Whether a full-screen app — a game, a video, a presentation — has the screen right now.
 *
 * Asks Windows the same question it asks itself before showing its own notifications
 * (`SHQueryUserNotificationState`), which covers exclusive full-screen Direct3D games as well as
 * borderless-windowed ones. The desktop card shows over borderless full-screen games (unless the
 * listener turns that off) and always stays away from exclusive full screen, where it can't be seen
 * and could knock the game out of full screen.
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

    /** What has the screen right now, as far as interrupting it goes. */
    enum class State {
        /** Ordinary desktop. */
        NONE,

        /** A full-screen app in a borderless window — a window on top of it shows. */
        BORDERLESS,

        /**
         * Exclusive full-screen Direct3D, or presentation mode. Nothing outside the game can draw
         * over exclusive full screen, and a top-most window appearing can knock the game out of it.
         */
        EXCLUSIVE,
    }

    fun state(): State {
        val api = shell32 ?: return State.NONE
        return runCatching {
            val state = IntByReference()
            if (api.SHQueryUserNotificationState(state) != 0) return@runCatching State.NONE
            when (state.value) {
                QUNS_BUSY -> State.BORDERLESS
                QUNS_RUNNING_D3D_FULL_SCREEN, QUNS_PRESENTATION_MODE -> State.EXCLUSIVE
                else -> State.NONE
            }
        }.getOrDefault(State.NONE)
    }

    fun active(): Boolean = state() != State.NONE
}
