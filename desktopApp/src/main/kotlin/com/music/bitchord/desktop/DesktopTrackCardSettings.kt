package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the "Now playing" card shows when the track changes. */
internal enum class TrackCardPlacement(val label: String) {
    IN_APP("Inside BitChord"),
    SCREEN("On the desktop"),
    OFF("Off"),
}

/** Which corner (or edge middle) the card sits in. */
internal enum class TrackCardCorner(val label: String, val top: Boolean, val horizontal: Int) {
    TOP_LEFT("Top left", top = true, horizontal = -1),
    TOP_CENTER("Top", top = true, horizontal = 0),
    TOP_RIGHT("Top right", top = true, horizontal = 1),
    BOTTOM_LEFT("Bottom left", top = false, horizontal = -1),
    BOTTOM_CENTER("Bottom", top = false, horizontal = 0),
    BOTTOM_RIGHT("Bottom right", top = false, horizontal = 1),
}

/** How the card is dressed. */
internal enum class TrackCardStyle(val label: String) {
    GLOSSY("Glossy"),
    GLASS("Artwork glass"),
    SOLID("Solid"),
    LIGHT("Light"),
}

/** How long the card stays, in milliseconds. */
internal enum class TrackCardDuration(val label: String, val millis: Long) {
    SHORT("3 s", 3_000),
    NORMAL("5 s", 5_000),
    LONG("8 s", 8_000),
}

/** The card's settings, persisted, and readable from anywhere as flows. */
internal object DesktopTrackCardSettings {
    private val persistence get() = DesktopPersistence()

    private inline fun <reified E : Enum<E>> load(key: String, default: E): MutableStateFlow<E> {
        val saved = persistence.string(key, default.name)
        return MutableStateFlow(enumValues<E>().firstOrNull { it.name == saved } ?: default)
    }

    private val _placement = load(KEY_PLACEMENT, TrackCardPlacement.IN_APP)
    private val _corner = load(KEY_CORNER, TrackCardCorner.BOTTOM_RIGHT)
    private val _style = load(KEY_STYLE, TrackCardStyle.GLOSSY)
    private val _duration = load(KEY_DURATION, TrackCardDuration.NORMAL)
    private val _overGames = MutableStateFlow(persistence.boolean(KEY_OVER_GAMES, true))

    val placement: StateFlow<TrackCardPlacement> = _placement
    val corner: StateFlow<TrackCardCorner> = _corner
    val style: StateFlow<TrackCardStyle> = _style
    val duration: StateFlow<TrackCardDuration> = _duration

    /** Whether the desktop card shows over full-screen games (borderless/windowed full screen). */
    val overGames: StateFlow<Boolean> = _overGames

    fun setPlacement(value: TrackCardPlacement) = save(KEY_PLACEMENT, value, _placement)
    fun setCorner(value: TrackCardCorner) = save(KEY_CORNER, value, _corner)
    fun setStyle(value: TrackCardStyle) = save(KEY_STYLE, value, _style)
    fun setDuration(value: TrackCardDuration) = save(KEY_DURATION, value, _duration)
    fun setOverGames(value: Boolean) {
        persistence.saveBoolean(KEY_OVER_GAMES, value)
        _overGames.value = value
    }

    private fun <E : Enum<E>> save(key: String, value: E, into: MutableStateFlow<E>) {
        persistence.saveString(key, value.name)
        into.value = value
    }

    private const val KEY_PLACEMENT = "track_card_placement"
    private const val KEY_CORNER = "track_card_corner"
    private const val KEY_STYLE = "track_card_style"
    private const val KEY_DURATION = "track_card_duration"
    private const val KEY_OVER_GAMES = "track_card_over_games"
}
