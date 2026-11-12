package com.so.svg599.musique

import androidx.media3.common.MediaItem

/** Filtre commun des résultats YouTube : titres musicaux enregistrés, sans flux live ni formats parlés. */
object YoutubeMusicFilter {
    const val MAX_DURATION_SECONDS = Int.MAX_VALUE

    private val nonMusic = Regex(
        "(?i)(?:\\b(?:news|actualité|actualités|politique|élections?|économie|finance|technologie|tech|informatique|ordinateur|processeur|intel|minios|fibre|plainte|plaintes|test produit|review|unboxing|tutoriel|tuto|gameplay|let['’]s play|walkthrough|vlog|documentaire|documentary|podcast|interview|reaction|réaction|trailer|bande-annonce)\\b|radio\\s+24/?7)"
    )
    private val musicWords = Regex("(?i)\\b(?:music|musique|song|chanson|official audio|official video|lyrics|paroles|album|single|live session|remix|mix|playlist|dj set|soundtrack|ost|instrumental|cover|concert|acoustic|beat|musical)\\b")
    private val musicTypes = setOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC")
    private val rejectedTypes = setOf("MUSIC_VIDEO_TYPE_PODCAST_EPISODE")

    /** Entrée commune pour le parsing JSON, avant la construction/l'ajout d'un MediaItem. */
    fun isMusic(title: String, durationSec: Long, isLive: Boolean, musicVideoType: String?, id: String?): Boolean {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) return false
        if (durationSec <= 0L) return false
        if (isLive || nonMusic.containsMatchIn(normalizedTitle)) return false
        val type = musicVideoType.orEmpty().uppercase()
        if (type in rejectedTypes) return false
        
        val isKnownMusic = type in musicTypes
        // Types structurés de YouTube Music / Music category passent; les résultats
        // génériques doivent avoir au moins un signal musical explicite.
        if (isKnownMusic) return true
        return musicWords.containsMatchIn(normalizedTitle)
    }

    /** Filtre pour les MediaItem dont les métadonnées YouTube sont conservées dans extras. */
    fun isMusic(item: MediaItem): Boolean {
        val extras = item.mediaMetadata.extras
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val duration = extras?.getLong("youtube_duration_seconds", -1L) ?: -1L
        val type = extras?.getString("youtube_music_video_type")
        val id = extras?.getString("youtube_playlist_id")
            ?: extras?.getString("youtube_video_id")
            ?: extras?.getString("youtube_video_id")
            ?: item.mediaId
        val musicRenderer = extras?.getBoolean("youtube_music_renderer", false) == true
        // Renderer musical connu peut manquer sa durée dans certaines réponses YouTube Music.
        if (duration <= 0L || extras?.getBoolean("youtube_live", false) == true) return false
        return isMusic(title, duration, false, type ?: if (musicRenderer) "MUSIC_VIDEO_TYPE_ATV" else null, id)
    }
}
