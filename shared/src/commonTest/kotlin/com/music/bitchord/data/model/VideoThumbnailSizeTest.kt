package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoThumbnailSizeTest {
    private val signed = "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg?sqp=-oaymwEWCJADEOEBIAQqCghqEJQEGHgg6AJIWg&rs=AMzJL3k"

    @Test
    fun playerSizeAsksForTheLargestThumbnailWithoutTheSignature() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", signed.artworkAt(PLAYER_ART_PX))
    }

    @Test
    fun smallSurfacesStayOnSizesEveryVideoHas() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", signed.artworkAt(CARD_ART_PX))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", signed.artworkAt(ROW_ART_PX))
    }

    @Test
    fun webpAndNumberedHostsAreRecognised() {
        assertEquals(
            "https://i.ytimg.com/vi/abc-DEF_123/maxresdefault.jpg",
            "https://i9.ytimg.com/vi_webp/abc-DEF_123/hqdefault.webp".artworkAt(PLAYER_ART_PX),
        )
    }

    @Test
    fun fallbackStepsDownToHqdefaultAndStops() {
        val maxres = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"
        val sd = maxres.youTubeThumbnailFallback()
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg", sd)
        val hq = sd!!.youTubeThumbnailFallback()
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", hq)
        assertNull(hq!!.youTubeThumbnailFallback())
    }

    @Test
    fun googleImageHostsKeepTheirSizeHintRewrite() {
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj".artworkAt(1200),
        )
        assertNull("https://lh3.googleusercontent.com/abc=w60-h60".youTubeThumbnailFallback())
    }
}
