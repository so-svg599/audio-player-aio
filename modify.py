from pathlib import Path
root=Path('/home/user/r66/app/src/main/java/com/so/svg599/musique')
p=root/'SoundCloudAccount.kt'; s=p.read_text()
s=s.replace('    fun isLoggedIn(): Boolean = !token.isNullOrBlank()','    fun isLoggedIn(): Boolean = !token.isNullOrBlank()\n\n    internal fun context(): Context? = appContext')
s=s.replace('    private const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"','    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"\n    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"\n    private const val PREFS = "soundcloud_client_id_cache"\n    private const val PREF_KEY = "last_valid_client_id"')
s=s.replace('    private fun tokenIsInvalid(token: String): Boolean = try {\n        val request = Request.Builder().url("$API/me?client_id=${clientId()}")\n            .header("Accept", "application/json").header("User-Agent", UA)\n            .header("Authorization", "OAuth $token").build()\n        http.newCall(request).execute().use { response -> response.code == 401 }\n    } catch (_: Exception) { false }','''    private fun tokenIsInvalid(token: String): Boolean {
        // Two independent /me checks are required before declaring the saved session invalid.
        return try {
            repeat(2) {
                val request = Request.Builder().url("$API/me")
                    .header("Accept", "application/json").header("User-Agent", UA)
                    .header("Authorization", "OAuth $token").build()
                val code = http.newCall(request).execute().use { it.code }
                if (code != 401) return false
            }
            true
        } catch (_: Exception) { false }
    }''')
s=s.replace('    private fun get(path: String, query: String, auth: Boolean = true): String =\n        send(auth) { cid -> "$API$path?${if (query.isNotBlank()) "$query&" else ""}client_id=$cid" }','''    private fun get(path: String, query: String, auth: Boolean = true): String =
        send(auth) { cid ->
            val params = query.takeIf { it.isNotBlank() }?.plus("&").orEmpty()
            if (cid.isNullOrBlank()) "$API$path?${params.trimEnd('&')}".trimEnd('?')
            else "$API$path?${params}client_id=$cid"
        }''')
s=s.replace('private fun send(auth: Boolean, buildUrl: (String) -> String): String {','private fun send(auth: Boolean, buildUrl: (String?) -> String): String {')
s=s.replace('''            val tokenSnapshot = token
            val builder = Request.Builder().url(buildUrl(clientId()))''','''            val tokenSnapshot = token
            // OAuth is preferred without client_id for authenticated /me and account endpoints.
            val useOauthOnly = auth && !tokenSnapshot.isNullOrBlank()
            val cid = if (useOauthOnly) null else clientId()
            val builder = Request.Builder().url(buildUrl(cid))''')
s=s.replace('''                    if (attempt == 0) {
                        runCatching { refreshClientId() } // Keep the second attempt even if discovery is temporarily offline.
                        token = ScAuth.reload() ?: token
                        return@repeat
                    }
                    if (auth && tokenSnapshot != null && r.code == 401 && tokenIsInvalid(tokenSnapshot))
                        throw ScAuthException("Session expirée")''','''                    if (attempt == 0) {
                        if (useOauthOnly) {
                            // Try the OAuth request with the cached/scraped public id; do not drop the session.
                            runCatching { clientId() }
                        } else runCatching { refreshClientId() }
                        token = ScAuth.reload() ?: token
                        return@repeat
                    }
                    if (auth && tokenSnapshot != null && r.code == 401 && pathIsMe(buildUrl) && tokenIsInvalid(tokenSnapshot))
                        throw ScAuthException("Session expirée")''')
# Path classification to ensure only /me 401 purges by exception (constructor gets the URL lambda)
s=s.replace('    private fun send(auth: Boolean, buildUrl: (String?) -> String): String {','    private fun send(auth: Boolean, buildUrl: (String?) -> String): String {')
s=s.replace('''    /**
     * Lien audio direct''','''    private fun pathIsMe(buildUrl: (String?) -> String): Boolean =
        runCatching { buildUrl(null).substringAfter(API).substringBefore('?') == "/me" }.getOrDefault(false)

    /**
     * Lien audio direct''')
# Replace refresh logic
start=s.index('    /** Identifiant public que le site SoundCloud lui-même utilise')
end=s.index('    // ---------- lecture des réponses ----------',start)
s=s[:start]+'''    /** Retourne l’identifiant public SoundCloud. Les candidats ne sont mémorisés qu’après validation API. */
    private fun clientId(): String {
        val now = System.currentTimeMillis()
        clientId?.takeIf { now - clientIdFetchedAt < clientIdRefreshMillis }?.let { return it }
        return refreshClientId(force = false)
    }

    @Synchronized
    private fun refreshClientId(force: Boolean): String {
        synchronized(clientIdLock) {
            val now = System.currentTimeMillis()
            clientId?.takeIf { !force && now - clientIdFetchedAt < clientIdRefreshMillis }?.let { return it }
            val prefs = ScAuth.context()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cached = prefs?.getString(PREF_KEY, null)?.takeIf { it.matches(Regex("[A-Za-z0-9]{20,}")) }
            val candidates = LinkedHashSet<String>()
            // Un OAuth valide reste utile même si les ressources publiques sont momentanément bloquées.
            ScAuth.token?.let { token ->
                val fromToken = Regex("(?i)(?:client_id[=:]?)\\\\s*[\\\"']?([A-Za-z0-9]{20,})").find(token)?.groupValues?.get(1)
                if (!fromToken.isNullOrBlank()) candidates += fromToken
            }
            val headers = listOf(UA, DESKTOP_UA)
            for (page in listOf("https://soundcloud.com/", "https://soundcloud.com/discover")) {
                for (ua in headers) {
                    val html = runCatching { fetchWithUa(page, ua) }.getOrNull() ?: continue
                    val scripts = Regex("(?i)<script[^>]+src=[\\\"'](https?://[^\\\"']*sndcdn\\.com/assets/[^\\\"']+\\.js(?:\\?[^\\\"']*)?)[\\\"']")
                        .findAll(html).map { it.groupValues[1] }.toList().asReversed()
                    for (script in scripts) {
                        val js = runCatching { fetchWithUa(script, ua) }.getOrNull() ?: continue
                        val patterns = listOf(
                            Regex("client_id\\\\s*:\\\\s*[\\\"']([A-Za-z0-9]{20,})[\\\"']"),
                            Regex("[\\\"']client_id[\\\"']\\\\s*:\\\\s*[\\\"']([A-Za-z0-9]{20,})[\\\"']"),
                            Regex("client_id=([A-Za-z0-9]{20,})"),
                            Regex("\\\\?client_id=([A-Za-z0-9]{32})"),
                        )
                        patterns.forEach { rx -> rx.findAll(js).forEach { candidates += it.groupValues[1] } }
                    }
                }
            }
            // Validation endpoint accepts a client_id even when the discovery page changes format.
            for (id in candidates + listOfNotNull(cached)) {
                if (validateClientId(id)) {
                    clientId = id
                    clientIdFetchedAt = System.currentTimeMillis()
                    prefs?.edit()?.putString(PREF_KEY, id)?.apply()
                    return id
                }
            }
            // Preserve and try the persisted known-good value on transient scraping/validation failures.
            cached?.let {
                clientId = it
                clientIdFetchedAt = System.currentTimeMillis()
                return it
            }
            throw IOException("Impossible de récupérer l'identifiant client SoundCloud: pages/ressources inaccessibles ou validation API refusée. Vérifiez la connexion et réessayez.")
        }
    }

    private fun validateClientId(id: String): Boolean = try {
        val url = "$API/search/tracks?q=music&limit=1&client_id=${URLEncoder.encode(id, "UTF-8")}"
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA).build())
            .execute().use { response -> response.isSuccessful && response.body?.string()?.trimStart()?.startsWith("{") == true }
    } catch (_: Exception) { false }

    private fun fetchWithUa(url: String, ua: String): String {
        val request = Request.Builder().url(url).header("User-Agent", ua).header("Accept", "text/html,application/javascript,*/*;q=0.8").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

''' +s[end:]
s=s.replace('send(false) { cid ->\n                    "$base?client_id=$cid"', 'send(false) { cid ->\n                    "$base?client_id=$cid"')
p.write_text(s)
# Apply remaining focused changes
p=root/'SoundCloudAccount.kt'; s=p.read_text()
s=s.replace('''            val tokenSnapshot = token
            // OAuth is preferred without client_id for authenticated /me and account endpoints.
            val useOauthOnly = auth && !tokenSnapshot.isNullOrBlank()
            val cid = if (useOauthOnly) null else clientId()''','''            val tokenSnapshot = token
            // OAuth-only first for authenticated endpoints; retry with the public id if rejected.
            val useOauthOnly = auth && !tokenSnapshot.isNullOrBlank() && attempt == 0
            val cid = if (useOauthOnly) null else clientId()''')
p.write_text(s)
# central filter
(root/'YoutubeMusicFilter.kt').write_text('''package com.so.svg599.musique

import androidx.media3.common.MediaItem

/** Filtre commun des résultats YouTube : musique enregistrée, pas les mix/lives/contenus parlés. */
object YoutubeMusicFilter {
    const val MAX_DURATION_SECONDS = 12 * 60
    private val nonMusic = Regex("(?i)(?:\\\\b(?:episode|épisode|ep\\.?|ép\\.?|série|serie|gameplay|let's play|vlog|tuto(?:riel)?|documentaire|documentary|trailer|bande-annonce|reaction|réaction)\\\\b)")
    private val liveTitle = Regex("(?i)(?:\\\\blive\\\\b|en direct|24\\s*/\\s*7\\s*(?:live|direct))")
    private val musicTypes = setOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC")
    private val rejectedTypes = setOf("MUSIC_VIDEO_TYPE_PODCAST_EPISODE")

    fun isMusic(item: MediaItem): Boolean {
        val title = item.mediaMetadata.title?.toString().orEmpty().trim()
        if (title.isBlank() || title.startsWith("Mix -", true) || title.startsWith("Mix –", true) || title.startsWith("Mix —", true)) return false
        if (nonMusic.containsMatchIn(title) || liveTitle.containsMatchIn(title)) return false
        val extras = item.mediaMetadata.extras
        val playlistId = extras?.getString("youtube_playlist_id").orEmpty()
        if (playlistId.startsWith("RD", true)) return false
        if (extras?.getBoolean("youtube_live", false) == true) return false
        val duration = extras?.getLong("youtube_duration_seconds", -1L) ?: -1L
        if (duration > MAX_DURATION_SECONDS) return false
        val type = extras?.getString("youtube_music_video_type").orEmpty()
        if (type in rejectedTypes) return false
        return type in musicTypes || extras?.getBoolean("youtube_music_renderer", false) == true
    }
}
''')
# YtMusic: attach type metadata from renderer on candidates, implicit typed status only music-specific renderer.
p=root/'YtMusic.kt'; s=p.read_text()
s=s.replace('''        return found.values.toList()
    }

    private fun rendererArtist''','''        return found.values.filter(YoutubeMusicFilter::isMusic)
    }

    private fun rendererArtist''',1)
# Add shared metadata extras to generic and lockup built items, derive from raw JSON
s=s.replace('''                    rendererDuration(renderer)?.let { putString("duration_text", it) }
                }).setIsBrowsable(false)''','''                    rendererDuration(renderer)?.let { putString("duration_text", it); putLong("youtube_duration_seconds", parseDurationSeconds(it)) }
                    putString("youtube_music_video_type", musicVideoType(renderer).orEmpty())
                    putBoolean("youtube_music_renderer", musicVideoType(renderer) in setOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC"))
                    putBoolean("youtube_live", isLiveOrMissingDuration(renderer))
                }).setIsBrowsable(false)''',2)
# parseMusicTracks extras mark musical renderer and duration / type from source, still returns accepted later in parseTracks filter
needle='''                            if (profileUrl != null) putString("creator_profile_url", profileUrl)
                            if (channelId != null) putString("creator_browse_id", channelId)
                        })'''
s=s.replace(needle,'''                            if (profileUrl != null) putString("creator_profile_url", profileUrl)
                            if (channelId != null) putString("creator_browse_id", channelId)
                            putBoolean("youtube_music_renderer", true)
                            putString("youtube_music_video_type", musicVideoType(r).orEmpty().ifBlank { "MUSIC_VIDEO_TYPE_ATV" })
                        })''',1)
# before rendererDuration add helpers
mark='    /** Durée affichée par les renderers browse'
helpers='''    private fun parseDurationSeconds(value: String): Long {
        val parts = value.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return -1L
        return parts.fold(0L) { total, part -> total * 60 + part }
    }

    private fun musicVideoType(node: JSONObject): String? {
        fun find(value: Any?, depth: Int = 0): String? {
            if (depth > 24) return null
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next(); val child = value.opt(key)
                        if (key.equals("musicVideoType", true) || key.equals("music_video_type", true)) {
                            val text = displayText(child).ifBlank { child?.toString().orEmpty() }
                            if (text.isNotBlank()) return text
                        }
                        find(child, depth + 1)?.let { return it }
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) find(value.opt(i), depth + 1)?.let { return it }
                is String -> if (value in setOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC", "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")) return value
            }
            return null
        }
        return find(node)
    }

'''
s=s.replace(mark,helpers+mark)
# uploads are channel content: no claim music-type (strict central filter) means filtered; apply check in builder
# related playlist id types only identifiers -> filter by titles/type parsers. Do not globally block item playlist id since video IDs not set.
p.write_text(s)
# StreamSources filter search and channel results only when source YouTube
p=root/'StreamSources.kt'; s=p.read_text()
s=s.replace('''        return found.values.take(MAX_SEARCH_RESULTS).map { item ->
            MediaItem.Builder()''','''        return found.values.take(MAX_SEARCH_RESULTS).map { item ->
            MediaItem.Builder()''',1)
# map output list ends after this block. easier assign list intermediate then filter
s=s.replace('''        return found.values.take(MAX_SEARCH_RESULTS).map { item ->''','''        val converted = found.values.take(MAX_SEARCH_RESULTS).map { item ->''',1)
# Replace first closing after map known anchor near comment
anchor='''                ).build()
        }
    }

    /** Liste les morceaux/vidéos publiés'''
s=s.replace(anchor,'''                ).build()
        }
        return if (source == YOUTUBE) converted.filter(YoutubeMusicFilter::isMusic) else converted
    }

    /** Liste les morceaux/vidéos publiés''',1)
# channel items mapped output variable and return filter
s=s.replace('''        return streams.values.take(MAX_CHANNEL_RESULTS).map { item ->''','''        val channelItems = streams.values.take(MAX_CHANNEL_RESULTS).map { item ->''',1)
anchor='''                    .setExtras(Bundle().apply {
                        putString("creator_profile_url", url)
                        putString("creator_browse_id", channel.id)
                    }).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
        }
    }'''
s=s.replace(anchor,'''                    .setExtras(Bundle().apply {
                        putString("creator_profile_url", url)
                        putString("creator_browse_id", channel.id)
                        putBoolean("youtube_music_renderer", false)
                    }).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
        }
        return channelItems.filter(YoutubeMusicFilter::isMusic)
    }''',1)
p.write_text(s)
# version and error text
p=root.parent/'..' # no-op
p=Path('/home/user/r66/app/build.gradle.kts'); s=p.read_text().replace('versionCode = 47','versionCode = 48').replace('versionName = "0.59"','versionName = "1.0"'); p.write_text(s)
p=root/'Ui.kt'; s=p.read_text().replace('Text("Musique • v0.38"','Text("Musique • v1.0"').replace('"Résultat vide — détail HTTP/JSON ci-dessus."','state.streamError ?: "Résultat vide — aucun contenu exploitable reçu."'); p.write_text(s)
# changelog add note
p=Path('/home/user/r66/CHANGEMENTS.md'); s=p.read_text(); note='''# R66 — musique YouTube et fiabilité SoundCloud (1.0 / versionCode 48)\n\n- `SoundCloudAccount.kt` : client_id découvert depuis soundcloud.com et /discover, HTML servi à deux UA, scripts sndcdn parcourus en ordre inverse et motifs multiples; candidats validés par l’API. Le dernier ID valide est conservé en SharedPreferences et jamais supprimé sur incident réseau. OAuth seul est essayé en priorité; repli client_id sur retry. Une déconnexion n’est signalée qu’après deux 401 confirmés de `/me`.\n- `YoutubeMusicFilter.kt`, `YtMusic.kt`, `StreamSources.kt` : filtrage commun des mix, directs, titres non musicaux, podcasts non musicaux et durées supérieures à 12 min; sélection des types ATV/OMV/UGC, et pages de radio/continuation filtrées par le parser central. Les miniatures `hqdefault.jpg` restent inchangées.\n- `Ui.kt` : version affichée `v1.0` et message d’échec contextualisé. Version `1.0`, code 48.\n- Contrôles statiques uniquement; compilation Android et essais réseau/compte SoundCloud non effectués.\n\n---\n\n'''; p.write_text(note+s)
