package com.so.svg599.musique

import android.net.Uri
import androidx.media3.common.MediaItem

/** Single source of truth for missing YouTube video thumbnails. */
object YouTubeArtwork {
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    /** Keep any nonblank artwork supplied by YouTube; otherwise derive hqdefault from the video URL. */
    fun resolve(mediaId: String?, pageUrl: String?, artworkUri: Uri?): Uri? {
        if (!artworkUri?.toString().isNullOrBlank()) return artworkUri
        val raw = mediaId?.removePrefix(StreamSources.ID_PREFIX)?.takeIf { it.isNotBlank() }
            ?: pageUrl?.takeIf { it.isNotBlank() }
            ?: return null
        val videoId = videoId(raw) ?: return null
        return Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
    }

    /** Normalize a mapped media item without replacing a real artwork URL. */
    fun apply(item: MediaItem): MediaItem {
        val artwork = resolve(item.mediaId, item.localConfiguration?.uri?.toString(), item.mediaMetadata.artworkUri)
            ?: return item
        if (artwork == item.mediaMetadata.artworkUri) return item
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(artwork).build()).build()
    }

    private fun videoId(value: String): String? {
        if (videoIdPattern.matches(value)) return value
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val isYouTube = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        if (!isYouTube) return null
        val id = when {
            host == "youtu.be" -> uri.pathSegments.lastOrNull()
            uri.path?.startsWith("/shorts/") == true || uri.path?.startsWith("/embed/") == true || uri.path?.startsWith("/live/") == true -> uri.pathSegments.getOrNull(1)
            else -> uri.getQueryParameter("v")
        }
        return id?.takeIf(videoIdPattern::matches)
    }
}
