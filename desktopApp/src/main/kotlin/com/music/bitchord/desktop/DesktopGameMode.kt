package com.music.bitchord.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Game mode: while a full-screen game has the screen, BitChord steps its own drawing down.
 *
 * A window behind a game is still drawn every frame if anything in it moves — the player's drifting
 * gradient, the lyrics' motion, an animated canvas video — and every one of those frames is GPU
 * time taken from the game. So while one runs: the drift and the lyric motion settle (the player's
 * own Reduce animation), frosted blur becomes a solid fill, and the canvas video stops. The music,
 * and the Now playing card, carry on. Everything comes back when the game closes or loses the screen.
 *
 * Checked every few seconds with one cheap Windows call; nothing runs on other systems.
 */
internal object DesktopGameMode {

    private const val KEY_ENABLED = "game_mode"
    private const val POLL_MS = 3_000L

    private val _enabled = MutableStateFlow(DesktopPersistence().boolean(KEY_ENABLED, true))
    /** The listener's switch for all of this (Settings → Appearance). */
    val enabled: StateFlow<Boolean> = _enabled

    private val _active = MutableStateFlow(false)
    /** Whether game mode is in effect right now. */
    val active: StateFlow<Boolean> = _active

    fun setEnabled(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY_ENABLED, value)
        _enabled.value = value
        if (!value) _active.value = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun install() {
        // The listener's own Reduce animation and Reduce blur, or game mode: either one calms the player.
        scope.launch {
            combine(DesktopAppearanceSettings.reduceAnimation, _active) { own, game -> own || game }
                .collect { DesktopPlayerSettings.reduceAnimation.value = it }
        }
        scope.launch {
            combine(DesktopAppearanceSettings.reduceDynamicBlur, _active) { own, game -> own || game }
                .collect { DesktopPlayerSettings.reduceDynamicBlur.value = it }
        }
        if (!DesktopPlatform.isWindows) return
        scope.launch {
            while (true) {
                // BitChord's own full-screen player counts as full screen too; that isn't a game.
                val gaming = _enabled.value && DesktopFullscreenApps.active() && !DesktopWindowVisibility.inFront()
                if (gaming != _active.value) {
                    _active.value = gaming
                    DesktopTrackLog.log(if (gaming) "game mode on: a full-screen app has the screen" else "game mode off")
                }
                delay(POLL_MS)
            }
        }
    }
}
