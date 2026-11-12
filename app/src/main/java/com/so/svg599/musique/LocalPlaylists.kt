package com.so.svg599.musique

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

/** Playlists locales légères persistées dans les préférences de l'application. */
object LocalPlaylists {
    private const val PREFS = "local_playlists"
    private const val KEY = "data"

    private fun read(context: Context): JSONObject = runCatching {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    fun names(context: Context): List<String> = read(context).keys().asSequence().toList().sorted()

    fun add(context: Context, name: String, item: MediaItem) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val data = read(context)
        val arr = data.optJSONArray(cleanName) ?: JSONArray()
        val md = item.mediaMetadata
        arr.put(JSONObject()
            .put("mediaId", item.mediaId)
            .put("uri", item.localConfiguration?.uri?.toString() ?: item.mediaId)
            .put("title", md.title?.toString().orEmpty())
            .put("artist", md.artist?.toString().orEmpty())
            .put("artwork", md.artworkUri?.toString().orEmpty()))
        data.put(cleanName, arr)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, data.toString()).apply()
    }

    fun tracks(context: Context, name: String): List<MediaItem> {
        val arr = read(context).optJSONArray(name) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("uri").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@mapNotNull null
            YouTubeArtwork.apply(MediaItem.Builder().setMediaId(o.optString("mediaId", uri.toString())).setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(o.optString("title")).setArtist(o.optString("artist"))
                    .setArtworkUri(o.optString("artwork").takeIf { it.isNotBlank() }?.let(Uri::parse)).setIsPlayable(true).build()).build())
        }
    }
}
