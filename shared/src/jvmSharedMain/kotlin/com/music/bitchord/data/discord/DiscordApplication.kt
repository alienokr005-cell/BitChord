package com.music.bitchord.data.discord

/**
 * BitChord's own Discord application, shared by the phone and desktop presence.
 *
 * Discord reads more off this id than the code suggests. The local socket on
 * the desktop titles the activity with the application's registered name, some
 * Discord surfaces do the same for a gateway activity that carries it, and an
 * activity with no `large_image` shows the application's icon. Both come from
 * the developer portal entry, so the name there has to stay "BitChord".
 *
 * It replaced Metrolist's id, inherited along with the Kizzy port, which made
 * presences read "Listening to Metrolist" wherever Discord preferred the
 * application's name to the activity's.
 */
object DiscordApplication {
    const val ID = "1543679166967193620"

    /**
     * The square app icon, sent as the presence's small image so Discord draws
     * a BitChord badge on the corner of the cover, the way it badges Spotify's.
     * A public URL rather than a portal asset, so it goes through the same
     * external-assets mirror as the artwork and needs no developer-portal upload.
     */
    const val LOGO_URL =
        "https://raw.githubusercontent.com/kushagrasinghx/BitChord/main/app/src/main/ic_launcher-playstore.png"

    /** Hover text for the badge. */
    const val LOGO_TEXT = "BitChord"
}
