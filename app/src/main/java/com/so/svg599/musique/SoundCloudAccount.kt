package com.so.svg599.musique

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ScAuthException(message: String) : IOException(message)

data class ScAudioStream(val url: String, val extension: String, val protocol: String)

/** Jeton de session SoundCloud (cookie oauth_token), stocké chiffré. */
object ScAuth {
    @Volatile var token: String? = null
        private set
    @Volatile private var appContext: Context? = null

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        "sc_auth_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(context: Context) {
        appContext = context.applicationContext
        token = runCatching { prefs(context).getString("token", null) }.getOrNull()
    }

    fun reload(): String? {
        val context = appContext ?: return token
        token = runCatching { prefs(context).getString("token", null) }.getOrNull()
        return token
    }

    fun save(context: Context, value: String) {
        appContext = context.applicationContext
        token = value
        // Commit encrypted token synchronously so WebView/process lifecycle cannot lose it.
        runCatching { prefs(context).edit().putString("token", value).commit() }
    }

    fun clear(context: Context) {
        appContext = context.applicationContext
        token = null
        runCatching { prefs(context).edit().clear().commit() }
    }

    fun isLoggedIn(): Boolean = !token.isNullOrBlank()

    internal fun context(): Context? = appContext
}

/**
 * Client de l'API interne de SoundCloud (api-v2), avec le jeton de ta session.
 * Non officiel : peut casser si SoundCloud change son site.
 */
object SoundCloudClient {
    private const val API = "https://api-v2.soundcloud.com"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val PREFS = "soundcloud_client_id_cache"
    private const val PREF_KEY = "last_valid_client_id"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var clientId: String? = null
    @Volatile private var clientIdFetchedAt: Long = 0L
    @Volatile private var userId: Long = 0L
    private val clientIdLock = Any()
    private val clientIdRefreshMillis = TimeUnit.MINUTES.toMillis(20)

    fun reset() { userId = 0L }

    /** Rafraîchit explicitement le client_id du site (échec non destructif). */
    fun refreshClientId() { refreshClientId(force = true) }

    /** Historique distant best-effort; SoundCloud retire parfois l’accès à cet endpoint privé. */
    fun history(): List<MediaItem> {
        val remote = runCatching { tracksOf(JSONObject(get("/me/play-history/tracks", "limit=50"))) }.getOrDefault(emptyList())
        if (remote.isNotEmpty()) return mergeLocalHistory(remote)
        return localHistory()
    }

    /** Persiste localement les titres SoundCloud réellement lancés pour compenser le 401 distant. */
    fun recordPlayed(item: MediaItem) {
        val url = item.mediaId.removePrefix(StreamSources.ID_PREFIX)
        if (!url.contains("soundcloud.com", true)) return
        val context = ScAuth.context() ?: return
        val extras = item.mediaMetadata.extras
        val o = JSONObject().put("url", url).put("title", item.mediaMetadata.title?.toString().orEmpty())
            .put("artist", item.mediaMetadata.artist?.toString().orEmpty())
            .put("art", item.mediaMetadata.artworkUri?.toString().orEmpty())
            .put("id", extras?.getString("sc_track_id").orEmpty())
        synchronized(this) {
            val prefs = context.getSharedPreferences("soundcloud_local_history", Context.MODE_PRIVATE)
            val list = runCatching { JSONArray(prefs.getString("tracks", "[]")) }.getOrDefault(JSONArray())
            val merged = JSONArray().put(o)
            for (i in 0 until list.length()) {
                val old = list.optJSONObject(i) ?: continue
                if (old.optString("url") != url && merged.length() < 100) merged.put(old)
            }
            prefs.edit().putString("tracks", merged.toString()).apply()
        }
    }

    private fun localHistory(): List<MediaItem> {
        val context = ScAuth.context() ?: return emptyList()
        val array = runCatching { JSONArray(context.getSharedPreferences("soundcloud_local_history", Context.MODE_PRIVATE).getString("tracks", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.startsWith("https://soundcloud.com/") } ?: return@mapNotNull null
            MediaItem.Builder().setMediaId(StreamSources.ID_PREFIX + url).setUri(url).setMediaMetadata(
                MediaMetadata.Builder().setTitle(o.optString("title").ifBlank { "Titre SoundCloud" })
                    .setArtist(o.optString("artist").ifBlank { "SoundCloud" })
                    .setArtworkUri(o.optString("art").takeIf { it.startsWith("http") }?.let { Uri.parse(it) })
                    .setExtras(Bundle().apply { o.optString("id").takeIf { it.isNotBlank() }?.let { putString("sc_track_id", it) } })
                    .setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
            ).build()
        }
    }

    private fun mergeLocalHistory(remote: List<MediaItem>): List<MediaItem> =
        (remote + localHistory()).distinctBy { it.mediaId }.take(100)

    /** Accueil personnalisé basé sur le compte connecté, ses likes et son historique. */
    fun home(): List<MediaItem> {
        val found = LinkedHashMap<String, MediaItem>()
        fun collect(node: Any?, depth: Int = 0) {
            if (depth > 12 || found.size >= 200) return
            when (node) {
                is JSONObject -> {
                    val candidate = if (node.has("track")) node.optJSONObject("track") else node
                    if (candidate?.has("permalink_url") == true && candidate.has("title") && candidate.has("id")) {
                        toItem(candidate)?.let { found.putIfAbsent(it.mediaId, it) }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) collect(node.opt(keys.next()), depth + 1)
                }
                is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), depth + 1)
            }
        }
        if (ScAuth.isLoggedIn()) {
            // Endpoints internes SoundCloud : certains comptes/API versions en supportent un sous-ensemble.
            listOf("/me/mixed-selections" to "limit=50", "/stream" to "limit=100").forEach { (path, query) ->
                runCatching { collect(JSONObject(get(path, query))) }
            }
            val liked = runCatching { likes() }.getOrDefault(emptyList())
            val played = runCatching { history() }.getOrDefault(emptyList())
            liked.take(30).forEach { found.putIfAbsent(it.mediaId, it) }
            played.take(30).forEach { found.putIfAbsent(it.mediaId, it) }
            val seeds = (liked.take(8) + played.take(8)).distinctBy { it.mediaId }
            seeds.forEach { seed ->
                runCatching { relatedTracks(seed).take(20).forEach { found.putIfAbsent(it.mediaId, it) } }
            }
            if (found.isNotEmpty()) return found.values.toList().shuffled().take(100)
        }
        // Fallback générique uniquement si non connecté ou aucun contenu personnalisé disponible.
        val trending = LinkedHashMap<String, MediaItem>()
        listOf("trending", "popular music").forEach { query ->
            if (trending.size < 30) runCatching { search(query).forEach { trending.putIfAbsent(it.mediaId, it) } }
        }
        return trending.values.take(100)
    }

    /** Titres liés à un titre SoundCloud par son identifiant API. */
    fun relatedTracks(item: MediaItem): List<MediaItem> {
        val id = item.mediaMetadata.extras?.getString("sc_track_id")?.toLongOrNull()
            ?: Regex("/tracks/(\\d+)").find(item.mediaId)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw IOException("Identifiant du titre SoundCloud introuvable")
        val root = JSONObject(get("/tracks/$id/related", "limit=50", auth = false))
        return items(root.optJSONArray("collection")).mapNotNull { toItem(it.optJSONObject("track") ?: it) }
            .distinctBy { it.mediaId }
    }

    fun uploads(): List<MediaItem> =
        items(JSONObject(get("/users/${me()}/tracks", "limit=50")).optJSONArray("collection"))
            .mapNotNull { toItem(it.optJSONObject("track") ?: it) }

    /** Recherche SoundCloud paginée via next_href / offset (API v2 non officielle). */
    fun search(query: String): List<MediaItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val pageSize = 50
        val maxPages = 3
        val results = LinkedHashMap<String, MediaItem>()
        var offset = 0
        var pageNumber = 0

        while (pageNumber < maxPages && results.size < 150) {
            val root = JSONObject(get(
                "/search/tracks",
                "q=$encoded&limit=$pageSize&offset=$offset&linked_partitioning=1",
                auth = false,
            ))
            val collection = items(root.optJSONArray("collection"))
            collection.mapNotNull { toItem(it) }.forEach { results.putIfAbsent(it.mediaId, it) }

            val nextHref = str(root, "next_href")
            val nextOffset = if (nextHref.isNotBlank()) {
                runCatching { Uri.parse(nextHref).getQueryParameter("offset")?.toIntOrNull() }.getOrNull()
            } else null

            when {
                nextOffset != null && nextOffset > offset -> offset = nextOffset
                collection.size >= pageSize -> offset += collection.size
                else -> break
            }
            pageNumber++
        }
        return results.values.toList()
    }

    fun likes(): List<MediaItem> = tracksOf(JSONObject(get("/users/${me()}/track_likes", "limit=50")))

    /** Titres publics d'un profil SoundCloud : ID connu d'abord, puis résolution du permalink. */
    fun artistTracks(userId: String?, profileUrl: String?): List<MediaItem> {
        var id = userId?.toLongOrNull()
        if (id == null && !profileUrl.isNullOrBlank()) {
            val resolved = JSONObject(get("/resolve", "url=" + URLEncoder.encode(profileUrl, "UTF-8"), auth = false))
            id = resolved.optLong("id").takeIf { it != 0L }
                ?: resolved.optJSONObject("user")?.optLong("id")?.takeIf { it != 0L }
        }
        val resolvedId = id ?: throw IOException("Profil SoundCloud introuvable")
        return items(JSONObject(get("/users/$resolvedId/tracks", "limit=50", auth = false)).optJSONArray("collection"))
            .mapNotNull { toItem(it.optJSONObject("track") ?: it) }
    }

    fun playlists(): List<AccountPlaylist> {
        val id = me()
        val out = LinkedHashMap<String, AccountPlaylist>()
        fun add(p: JSONObject?) {
            if (p == null) return
            val pid = p.optLong("id").takeIf { it != 0L }?.toString() ?: return
            val title = str(p, "title").ifBlank { return }
            val owner = str(p.optJSONObject("user"), "username")
            val subtitle = listOf("${p.optInt("track_count")} titres", owner).filter { it.isNotBlank() }.joinToString(" · ")
            out.putIfAbsent(pid, AccountPlaylist(pid, title, subtitle))
        }
        items(JSONObject(get("/users/$id/playlists_without_albums", "limit=50")).optJSONArray("collection")).forEach { add(it) }
        runCatching {
            items(JSONObject(get("/users/$id/playlist_likes", "limit=50")).optJSONArray("collection"))
                .forEach { add(it.optJSONObject("playlist")) }
        }
        return out.values.toList()
    }

    fun playlistTracks(id: String): List<MediaItem> {
        val tracks = items(JSONObject(get("/playlists/$id", "")).optJSONArray("tracks"))
        val full = HashMap<Long, JSONObject>()
        val missing = mutableListOf<Long>()
        tracks.forEach { t -> if (str(t, "title").isNotBlank()) full[t.optLong("id")] = t else missing += t.optLong("id") }
        // Les playlists longues ne renvoient que l'identifiant des derniers titres : on complète par lots.
        missing.chunked(30).forEach { chunk ->
            items(JSONArray(get("/tracks", "ids=" + chunk.joinToString(",")))).forEach { full[it.optLong("id")] = it }
        }
        return tracks.mapNotNull { toItem(full[it.optLong("id")]) }
    }

    // ---------- requêtes ----------

    private fun me(): Long {
        if (userId != 0L) return userId
        val id = JSONObject(get("/me", "")).optLong("id")
        if (id == 0L) throw ScAuthException("Compte SoundCloud introuvable, reconnecte-toi")
        userId = id
        return id
    }

    /** Confirm token invalidity without sending this check through the retry/error classifier. */
    private fun tokenIsInvalid(token: String): Boolean {
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
    }

    private fun get(path: String, query: String, auth: Boolean = true): String =
        // L'endpoint privé d'historique attend simultanément le jeton utilisateur et le client_id.
        send(auth, includeClientIdWithOAuth = path == "/me/play-history/tracks") { cid ->
            val params = query.takeIf { it.isNotBlank() }?.plus("&").orEmpty()
            if (cid.isNullOrBlank()) "$API$path?${params.trimEnd('&')}".trimEnd('?')
            else "$API$path?${params}client_id=$cid"
        }

    private fun send(auth: Boolean, includeClientIdWithOAuth: Boolean = false, buildUrl: (String?) -> String): String {
        var token = ScAuth.token
        if (auth && token == null) throw ScAuthException("Non connecté à SoundCloud")
        // OAuth, rafraîchissement du client_id, puis fallback public anonyme. Les endpoints
        // publics ne doivent pas hériter d'un jeton utilisateur potentiellement périmé.
        repeat(3) { attempt ->
            val tokenSnapshot = if (auth && attempt < 2) token else null
            val useOauthOnly = auth && !tokenSnapshot.isNullOrBlank() && attempt == 0 && !includeClientIdWithOAuth
            val cid = when {
                useOauthOnly -> null
                includeClientIdWithOAuth -> runCatching { clientId() }.getOrNull()
                attempt == 0 -> clientId()
                else -> runCatching { clientId() }.getOrNull()
            }
            val builder = Request.Builder().url(buildUrl(cid))
                .header("Accept", "application/json")
                .header("User-Agent", UA)
            if (tokenSnapshot != null) builder.header("Authorization", "OAuth $tokenSnapshot")
            http.newCall(builder.build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 401 || r.code == 403) {
                    if (attempt < 2) {
                        if (attempt == 0) runCatching { refreshClientId() }
                        token = ScAuth.reload() ?: token
                        return@repeat
                    }
                    if (auth && tokenSnapshot != null && r.code == 401 && pathIsMe(buildUrl) && tokenIsInvalid(tokenSnapshot))
                        throw ScAuthException("Session SoundCloud expirée : reconnectez-vous")
                    if (auth && pathIsMe(buildUrl)) throw ScAuthException("Cette opération SoundCloud nécessite une session valide : reconnectez-vous")
                    throw IOException("SoundCloud refuse cet endpoint privé (HTTP ${r.code})")
                }
                if (!r.isSuccessful) throw IOException("SoundCloud a répondu ${r.code}")
                if (body.isBlank()) {
                    if (attempt < 2) { if (attempt == 0) refreshClientId(); return@repeat }
                    throw IOException("Réponse SoundCloud vide après nouvelle tentative")
                }
                try {
                    if (body.trimStart().startsWith("{")) JSONObject(body)
                    else if (body.trimStart().startsWith("[")) JSONArray(body)
                    else throw IOException("Réponse SoundCloud illisible")
                } catch (e: Exception) {
                    if (attempt < 2) { if (attempt == 0) refreshClientId(); return@repeat }
                    throw IOException("Réponse SoundCloud illisible après nouvelle tentative", e)
                }
                return body
            }
        }
        throw IOException("Échec transitoire de la requête SoundCloud")
    }

    private fun pathIsMe(buildUrl: (String?) -> String): Boolean =
        runCatching { buildUrl(null).substringAfter(API).substringBefore('?') == "/me" }.getOrDefault(false)

    /** Résout toutes les variantes utilisables dans l'ordre demandé, en conservant leur format réel. */
    fun downloadStreams(pageUrl: String): List<ScAudioStream> {
        val track = JSONObject(get("/resolve", "url=" + URLEncoder.encode(pageUrl, "UTF-8"), auth = false))
        val trackAuth = str(track, "track_authorization")
        fun protocolOf(t: JSONObject) = t.optJSONObject("format")?.optString("protocol").orEmpty().lowercase()
        fun mimeOf(t: JSONObject) = t.optJSONObject("format")?.optString("mime_type").orEmpty().lowercase()
        fun extension(t: JSONObject): String {
            val m = mimeOf(t)
            return when {
                m.contains("mpeg") -> "mp3"
                m.contains("opus") || m.contains("ogg") || m.contains("webm") -> "opus"
                m.contains("mp4") || m.contains("aac") -> "m4a"
                else -> "m4a"
            }
        }
        val items = items(track.optJSONObject("media")?.optJSONArray("transcodings"))
            .filter { it.optString("url").isNotBlank() }
            .filter { protocolOf(it) == "progressive" || protocolOf(it) == "hls" }
            .filter { mimeOf(it).let { m -> m.contains("mpeg") || m.contains("mp4") || m.contains("aac") || m.contains("opus") || m.contains("ogg") || m.contains("webm") } }
        val ranked = items.sortedBy { t ->
            val protocol = protocolOf(t); val mime = mimeOf(t)
            when {
                protocol == "progressive" && mime.contains("mpeg") -> 0
                protocol == "hls" && (mime.contains("opus") || mime.contains("ogg") || mime.contains("webm")) -> 1
                protocol == "hls" && (mime.contains("mp4") || mime.contains("aac")) -> 2
                else -> 3
            }
        }
        val result = mutableListOf<ScAudioStream>()
        for (t in ranked) {
            try {
                val base = t.optString("url")
                val json = JSONObject(send(false) { cid ->
                    "$base?client_id=$cid" + if (trackAuth.isNotBlank()) "&track_authorization=${URLEncoder.encode(trackAuth, "UTF-8")}" else ""
                })
                val url = str(json, "url")
                if (url.startsWith("https://")) result += ScAudioStream(url, extension(t), protocolOf(t))
            } catch (_: IOException) { /* tenta la variante seguente */ }
        }
        if (result.isEmpty()) throw IOException("Aucun flux audio lisible pour ce titre")
        return result
    }

    /** Lien audio direct; progressive MP3 par défaut, puis les variantes HLS tolérantes. */
    fun streamUrl(pageUrl: String, preferHls: Boolean = false): String {
        val streams = downloadStreams(pageUrl)
        return (if (preferHls) streams.sortedBy { if (it.protocol == "hls") 0 else 1 } else streams).first().url
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** Retourne l’identifiant public SoundCloud. Les candidats ne sont mémorisés qu’après validation API. */
    /** Client id courant exposé aux suggestions de recherche (endpoint public). */
    fun currentClientIdForSuggestions(): String = clientId()

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
                val fromToken = Regex("(?i)(?:client_id[=:]?)\\s*[\"']?([A-Za-z0-9]{20,})").find(token)?.groupValues?.get(1)
                if (!fromToken.isNullOrBlank()) candidates += fromToken
            }
            val headers = listOf(UA, DESKTOP_UA)
            for (page in listOf("https://soundcloud.com/", "https://soundcloud.com/discover")) {
                for (ua in headers) {
                    val html = runCatching { fetchWithUa(page, ua) }.getOrNull() ?: continue
                    val scripts = Regex("(?i)<script[^>]+src=[\"'](https?://[^\"']*sndcdn\\.com/assets/[^\"']+\\.js(?:\\?[^\"']*)?)[\"']")
                        .findAll(html).map { it.groupValues[1] }.toList().asReversed()
                    for (script in scripts) {
                        val js = runCatching { fetchWithUa(script, ua) }.getOrNull() ?: continue
                        val patterns = listOf(
                            Regex("client_id\\s*:\\s*[\"']([A-Za-z0-9]{20,})[\"']"),
                            Regex("[\"']client_id[\"']\\s*:\\s*[\"']([A-Za-z0-9]{20,})[\"']"),
                            Regex("client_id=([A-Za-z0-9]{20,})"),
                            Regex("\\?client_id=([A-Za-z0-9]{32})"),
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

    // ---------- lecture des réponses ----------

    private fun tracksOf(root: JSONObject): List<MediaItem> =
        items(root.optJSONArray("collection")).mapNotNull { toItem(it.optJSONObject("track")) }

    private fun toItem(t: JSONObject?): MediaItem? {
        if (t == null || str(t, "policy") == "BLOCK") return null
        val url = str(t, "permalink_url").ifBlank { return null }
        val title = str(t, "title").ifBlank { return null }
        val user = t.optJSONObject("user")
        val artist = str(user, "username").ifBlank { "SoundCloud" }
        val art = str(t, "artwork_url").ifBlank { str(user, "avatar_url") }.replace("-large", "-t300x300")
        return MediaItem.Builder()
            .setMediaId(StreamSources.ID_PREFIX + url)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(art.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                    .setExtras(Bundle().apply {
                        val profile = str(user, "permalink_url").ifBlank {
                            str(user, "username").takeIf { it.isNotBlank() }?.let { "https://soundcloud.com/${it.replace(" ", "-").lowercase()}" }.orEmpty()
                        }
                        if (profile.startsWith("https://soundcloud.com/")) putString("creator_profile_url", profile)
                        user?.optLong("id")?.takeIf { it != 0L }?.let { putString("creator_id", it.toString()) }
                        if (profile.startsWith("https://soundcloud.com/")) putString("creator_permalink", profile)
                        t.optLong("id").takeIf { it != 0L }?.let { putString("sc_track_id", it.toString()) }
                    })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            ).build()
    }

    private fun items(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    private fun str(o: JSONObject?, key: String): String =
        if (o == null || o.isNull(key)) "" else o.optString(key)
}
