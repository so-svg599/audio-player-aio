package com.so.svg599.musique

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Source locale (MediaStore). Les sources réseau suivront le même modèle : fournir une liste de MediaItem. */
object LocalLibrary {

    fun tracks(context: Context): List<MediaItem> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val out = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val albumId = c.getLong(4)
                val artworkUri = if (albumId > 0L) android.net.Uri.parse("content://media/external/audio/albumart/$albumId") else null
                out += MediaItem.Builder()
                    .setMediaId("local:$id")
                    .setUri(ContentUris.withAppendedId(collection, id))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(c.getString(1))
                            .setArtist(c.getString(2))
                            .setAlbumTitle(c.getString(3))
                            .setArtworkUri(artworkUri)
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build()
                    ).build()
            }
        }
        return out
    }
}
