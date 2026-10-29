package com.music.bitchord.desktop

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.music.bitchord.data.model.youTubeThumbnailFallback
import java.util.concurrent.ConcurrentHashMap

/**
 * The app-wide Coil loader: Coil's defaults plus [YouTubeThumbnailFallback].
 *
 * Installed once, before the first image is asked for, so every `AsyncImage`, the player's cover,
 * the mesh backdrop and the palette all share it.
 */
internal object DesktopImageLoader {
    fun install() {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(YouTubeThumbnailFallback) }
                .build()
        }
    }
}

/**
 * Steps a video thumbnail down a size when the one asked for isn't there.
 *
 * The player asks for a music video's `maxresdefault` so its cover is sharp at full screen, and most
 * videos have one — but not all, and YouTube answers a missing size with a 404. Rather than leave
 * the player on its placeholder, this tries `sddefault` and then `hqdefault`, which every video has.
 * A size found missing is remembered for the session so the next draw of it goes straight to the
 * one that exists.
 */
internal object YouTubeThumbnailFallback : Interceptor {
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [url], skipping past sizes already known to be missing. */
    fun resolve(url: String): String {
        var current = url
        while (current in missing) current = current.youTubeThumbnailFallback() ?: return current
        return current
    }

    fun markMissing(url: String) {
        missing += url
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data as? String ?: return chain.proceed()
        if (data.youTubeThumbnailFallback() == null && data !in missing) return chain.proceed()
        var url = resolve(data)
        while (true) {
            val request = if (url == data) chain.request else chain.request.newBuilder().data(url).build()
            val result = chain.withRequest(request).proceed()
            if (result !is ErrorResult) return result
            val next = url.youTubeThumbnailFallback() ?: return result
            markMissing(url)
            url = next
        }
    }
}
