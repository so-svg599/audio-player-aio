package com.so.svg599.musique

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class AccountPlaylist(val id: String, val title: String, val subtitle: String)

/** Actions de l'écran YouTube Music, regroupées pour ne pas alourdir les signatures. */
class AccountActions(
    val openLogin: () -> Unit,
    val closeLogin: () -> Unit,
    val onCookie: (String) -> Unit,
    val logout: () -> Unit,
    val show: (String) -> Unit,
    val openPlaylist: (String, String) -> Unit,
)

class YtAuthException(message: String) : IOException(message)

/** Cookies de session YouTube Music, stockés chiffrés (Android Keystore). */
object YtAuth {
    @Volatile var cookie: String? = null
        private set

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        "yt_auth_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(context: Context) {
        cookie = runCatching { prefs(context).getString("cookie", null) }.getOrNull()
    }

    fun save(context: Context, value: String) {
        cookie = value
        runCatching { prefs(context).edit().putString("cookie", value).apply() }
    }

    fun clear(context: Context) {
        cookie = null
        runCatching { prefs(context).edit().clear().apply() }
    }

    fun isLoggedIn(): Boolean = !cookie.isNullOrBlank()
}

/** Nettoie les sous-titres YouTube pour ne conserver que le premier segment artiste pertinent. */
fun cleanArtist(value: String): String {
    var text = value.replace('\u00a0', ' ').trim()
    if (text.isBlank()) return ""
    val typePrefix = Regex("(?i)^\\s*(?:titre|chanson|vidéo|video|song|single|album|ep)\\s*[:\\-]?\\s*•\\s*")
    val noise = Regex("(?i)\\b(?:\\d[\\d,.]*\\s*(?:k|m|md|bn|million(?:s)?|milliard(?:s)?)?\\s*(?:de\\s+)?(?:vues?|views?|visionnements?|lectures?)|(?:de\\s+)?(?:vues?|views?|visionnements?|lectures?))\\b|\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b|\\bil\\s+y\\s+a\\b")
    text = text.replace(typePrefix, "").trim()
    val segments = text.split(Regex("\\s*[•|·]\\s*"))
    val cleaned = mutableListOf<String>()
    for (part in segments) {
        var candidate = part.trim().replace(typePrefix, "").trim()
        if (candidate.isBlank()) continue
        val match = noise.find(candidate)
        if (match != null) candidate = candidate.substring(0, match.range.first).trim().trimEnd('-', '–', '·', '|', ',')
        if (candidate.isBlank() || Regex("(?i)^\\s*(?:\\d[\\d,.]*\\s*)?(?:vues?|views?|visionnements?|lectures?)\\s*$").matches(candidate)) continue
        if (Regex("(?i)^il\\s+y\\s+a\\b").containsMatchIn(candidate)) continue
        cleaned += candidate
    }
    // Un texte sans séparateur peut encore commencer par le type, ou mêler views/durée après l’artiste.
    if (cleaned.isEmpty()) {
        var candidate = text.replace(typePrefix, "").trim()
        val match = noise.find(candidate)
        if (match != null) candidate = candidate.substring(0, match.range.first).trim()
        return candidate
    }
    return cleaned.first()
}

/**
 * Client de l'API interne de YouTube Music (même principe que YMusic / ytmusicapi).
 * Non documentée : peut casser quand YouTube la modifie. La lecture des réponses
 * cherche les blocs par leur nom plutôt que par leur position, pour mieux résister.
 */
object YtMusicClient {
    private const val ORIGIN = "https://music.youtube.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun uploads(): List<MediaItem> {
        val failures = mutableListOf<String>()
        var channelId: String? = null
        var profileUrl: String? = null
        runCatching { requestAccountMenu() }
            .onSuccess { menu ->
                channelId = channelIdFromAccountMenu(menu)
                profileUrl = channelProfileFromAccountMenu(menu)
            }
            .onFailure { failures += "account/account_menu: ${it.message ?: it.javaClass.simpleName}" }

        // Si le menu ne livre pas le channelId, charger le lien de profil du compte
        // avec le même cookie de session, puis extraire l'identifiant canonique UC.
        if (channelId.isNullOrBlank() && profileUrl.isNullOrBlank()) {
            runCatching {
                val homeHtml = fetchChannelPage("https://www.youtube.com/")
                val initial = extractInitialData(homeHtml)
                channelId = findChannelId(initial)
                profileUrl = channelProfileFromAccountMenu(requestAccountMenu())
            }.onFailure { failures += "profil/session utilisateur: ${it.message ?: it.javaClass.simpleName}" }
        }
        if (channelId.isNullOrBlank() && !profileUrl.isNullOrBlank()) {
            runCatching { fetchChannelPage(profileUrl!!).let { html ->
                channelId = extractChannelIdFromHtml(html) ?: findChannelId(extractInitialData(html))
            } }.onFailure { failures += "profil utilisateur (${profileUrl}): ${it.message ?: it.javaClass.simpleName}" }
        }
        if (channelId.isNullOrBlank()) {
            runCatching { findChannelId(browse("FEmusic_library_landing")) }
                .onSuccess { channelId = it }
                .onFailure { failures += "FEmusic_library_landing: ${it.message ?: it.javaClass.simpleName}" }
        }

        val channel = channelId?.takeIf { it.startsWith("UC") }
        var firstLockupDiagnostic: String? = null
        if (channel == null) failures += "identification de la chaîne: aucun channelId UC trouvé"
        if (channel != null) {
            val videoParams = "EgZ2aWRlb3PyBgQKAjoA"
            // Extraire d'abord le véritable endpoint de l'onglet Vidéos s'il est déclaré
            // dans la page chaîne. Certains canaux répondent à browse(channelId) sans onglet.
            val declaredVideoTab = runCatching {
                findVideoTab(extractInitialData(fetchChannelPage("https://www.youtube.com/channel/$channel/videos")))
            }.onFailure { failures += "détection onglet chaîne Vidéos: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
            val browseTargets = buildList {
                declaredVideoTab?.let { (id, params) -> add(Triple(id, params ?: videoParams, "onglet Vidéos déclaré")) }
                add(Triple(channel, videoParams, "params Vidéos connus"))
            }.distinctBy { it.first to it.second }
            // Chemin prioritaire: endpoint du tab Vidéos puis params connus de l'onglet,
            // avec les mêmes cookies/SAPISIDHASH que les autres requêtes authentifiées.
            for ((browseId, params, label) in browseTargets) for (client in listOf("WEB", "WEB_REMIX")) {
                val pages = runCatching {
                    browseAllPagesClient(browseId, client, maxPages = 20, params = params)
                }.onFailure { failures += "browse chaîne $browseId ($label)/$client: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
                if (pages != null) {
                    if (firstLockupDiagnostic == null) firstLockupDiagnostic = pages.asSequence()
                        .mapNotNull(::firstLockupExcerpt).firstOrNull()
                    val tracks = pages.flatMap { robustUploadTracks(it) + parseTracks(it) }.distinctBy { it.mediaId }
                    if (tracks.isNotEmpty()) return tracks
                    failures += "browse chaîne $browseId ($label)/$client: ${pages.firstOrNull()?.let(::responseDiagnostic) ?: "réponse JSON vide"}; aucune vidéo exploitable"
                }
            }

            // Le parseur d'uploads est volontairement indépendant des filtres usuels
            // (durée, contentType, renderer connu) : WEB renvoie désormais des lockups.
            // Conserver aussi parseTracks pour les réponses historiques.
            fun uploadsFrom(pages: List<JSONObject>): List<MediaItem> = pages
                .flatMap { robustUploadTracks(it) + parseTracks(it) }.distinctBy { it.mediaId }

            // Fallbacks InnerTube: playlist système UU/VLUU sur plusieurs clients.
            for (browseId in listOf("VL" + "UU" + channel.removePrefix("UC"), "UU" + channel.removePrefix("UC"))) {
                for (client in listOf("WEB", "ANDROID", "TVHTML5")) {
                    val pages = runCatching { browseAllPagesClient(browseId, client, maxPages = 20) }
                        .onFailure { failures += "browse playlist uploads $browseId/$client: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
                    if (pages != null) {
                        if (firstLockupDiagnostic == null) firstLockupDiagnostic = pages.asSequence()
                            .mapNotNull(::firstLockupExcerpt).firstOrNull()
                        val tracks = uploadsFrom(pages)
                        if (tracks.isNotEmpty()) return tracks
                        failures += "browse playlist uploads $browseId/$client: ${pages.firstOrNull()?.let(::responseDiagnostic) ?: "réponse JSON vide"}; aucune vidéo exploitable"
                    }
                }
            }

            // Endpoint search de secours, lié explicitement à l'identifiant de chaîne.
            for (client in listOf("WEB", "ANDROID", "TVHTML5")) {
                val page = runCatching { searchChannelUploads(channel, client) }
                    .onFailure { failures += "search chaîne/$client: ${it.message ?: it.javaClass.simpleName}" }.getOrNull() ?: continue
                val tracks = uploadsFrom(listOf(page))
                if (tracks.isNotEmpty()) return tracks
                failures += "search chaîne/$client: ${responseDiagnostic(page)}; aucune vidéo exploitable"
            }

            // Dernier recours: page HTML /channel/UC.../videos + ytInitialData.
            runCatching { fetchChannelPage("https://www.youtube.com/channel/$channel/videos") }
                .onSuccess { html ->
                    val initial = runCatching { extractInitialData(html) }.getOrElse {
                        failures += "HTML chaîne /videos: ${it.message ?: it.javaClass.simpleName}"
                        null
                    }
                    if (initial != null) {
                        findVideoTab(initial)?.let { (tabId, tabParams) ->
                            val tabPages = runCatching {
                                browseAllPagesClient(tabId, "WEB", maxPages = 20, params = tabParams ?: videoParams)
                            }.onFailure { failures += "HTML chaîne onglet $tabId: ${it.message ?: it.javaClass.simpleName}" }.getOrNull().orEmpty()
                            if (firstLockupDiagnostic == null) firstLockupDiagnostic = tabPages.asSequence()
                                .mapNotNull(::firstLockupExcerpt).firstOrNull()
                            val fromTab = tabPages.flatMap { robustUploadTracks(it) + parseTracks(it) }.distinctBy { it.mediaId }
                            if (fromTab.isNotEmpty()) return fromTab
                        }
                        if (firstLockupDiagnostic == null) firstLockupDiagnostic = firstLockupExcerpt(initial)
                        val tracks = (robustUploadTracks(initial) + parseTracks(initial)).distinctBy { it.mediaId }
                        if (tracks.isNotEmpty()) return tracks
                        failures += "HTML chaîne /videos: ${responseDiagnostic(initial)}; aucune vidéo exploitable"
                    }
                }
                .onFailure { failures += "HTML chaîne /videos: ${it.message ?: it.javaClass.simpleName}" }
        }
        // Dernier recours: RSS public (jusqu'à 15 vidéos récentes), sans authentification.
        if (channel != null) runCatching { uploadsFromRss(channel) }
            .onSuccess { if (it.isNotEmpty()) return it }
            .onFailure { failures += "RSS chaîne: ${it.message ?: it.javaClass.simpleName}" }
        val conciseFailures = failures.joinToString("; ").take(1800)
        val lockupSuffix = firstLockupDiagnostic?.let { "; premier lockupViewModel brut (300 caractères max)=${it.take(300)}" }.orEmpty()
        throw IOException("Uploads YouTube indisponibles (${if (YtAuth.isLoggedIn()) "session connectée" else "non connecté"}) — $conciseFailures$lockupSuffix")
    }

    private fun fetchChannelPage(url: String): String {
        val cookie = YtAuth.cookie ?: throw YtAuthException("Non connecté à YouTube Music")
        val request = Request.Builder().url(url).get().header("User-Agent", UA).header("Cookie", cookie)
            .header("Referer", "https://www.youtube.com/").header("Accept-Language", "fr-FR,fr;q=0.9").build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${body.take(300).replace(Regex("\\s+"), " ")}")
            if (body.isBlank()) throw IOException("HTTP ${response.code}: réponse vide")
            return body
        }
    }

    private fun extractChannelIdFromHtml(html: String): String? =
        Regex("""(?:channelId|externalId|browseId)[\"']?\s*[:=]\s*[\"'](UC[A-Za-z0-9_-]{20,})""")
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""/channel/(UC[A-Za-z0-9_-]{20,})""").find(html)?.groupValues?.getOrNull(1)

    /** File radio/watch-next liée à une vidéo sélectionnée depuis l'accueil YouTube. */
    fun relatedTracks(videoId: String): List<MediaItem> {
        require(videoId.isNotBlank()) { "Identifiant YouTube manquant" }
        val failures = mutableListOf<String>()
        val variants = listOf("WEB_REMIX", "WEB", "ANDROID_MUSIC", "WEB_REMOTE")
        val playlistIds = listOf("RD$videoId", "RDAMVM$videoId")
        for (playlistId in playlistIds) {
            for (client in variants) {
                val origin = if (client == "ANDROID_MUSIC") ORIGIN else "https://www.youtube.com"
                val response = runCatching {
                    val body = JSONObject().put("context", contextFor(client)).put("videoId", videoId)
                        .put("playlistId", playlistId).put("params", "wAEB")
                    requestYouTubeiForOrigin("next", body, origin)
                }.onFailure { failures += "$playlistId/$client: ${it.message ?: it.javaClass.simpleName}" }.getOrNull() ?: continue
                val tracks = parseTracks(response).distinctBy { it.mediaId }
                if (tracks.size > 1) return tracks
                failures += "$playlistId/$client: ${responseDiagnostic(response)}"
                val continuation = runCatching {
                    nextContinuation(response = response, client = client)
                }.onFailure { failures += "$playlistId/$client continuation: ${it.message ?: it.javaClass.simpleName}" }
                    .getOrDefault(emptyList())
                if (continuation.size > 1) return continuation
            }
        }
        // Dernier recours : certaines radios ne sont renvoyées que par la page watch HTML.
        val html = runCatching { requestWatchHtml(videoId) }
            .onFailure { failures += "watch HTML: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
        if (html != null) {
            val tracks = parseTracks(extractInitialData(html)).distinctBy { it.mediaId }
            if (tracks.size > 1) return tracks
            failures += "watch HTML: données radio sans plus d'un renderer vidéo"
        }
        throw IOException("Mix indisponible — ${failures.joinToString("; ")}")
    }

    private fun nextContinuation(response: JSONObject, client: String): List<MediaItem> {
        val origin = if (client == "ANDROID_MUSIC") ORIGIN else "https://www.youtube.com"
        val result = LinkedHashMap<String, MediaItem>()
        // La réponse `next` déjà obtenue contient la première page; on l'utilise
        // comme point de départ pour poursuivre via son jeton, sans la redemander.
        var page = response
        parseTracks(page).forEach { result.putIfAbsent(it.mediaId, it) }
        val seen = mutableSetOf<String>()
        repeat(20) {
            val token = continuationToken(page) ?: return result.values.toList()
            if (!seen.add(token)) return result.values.toList()
            page = requestYouTubeiForOrigin("next", JSONObject().put("context", contextFor(client))
                .put("continuation", token), origin)
            parseTracks(page).forEach { result.putIfAbsent(it.mediaId, it) }
            if (result.size > 1) return result.values.toList()
        }
        return result.values.toList()
    }

    private fun requestWatchHtml(videoId: String): String {
        val cookie = YtAuth.cookie ?: throw YtAuthException("Non connecté à YouTube Music")
        val url = "https://www.youtube.com/watch?v=${Uri.encode(videoId)}&list=RD${Uri.encode(videoId)}"
        val request = Request.Builder().url(url).get().header("User-Agent", UA).header("Cookie", cookie)
            .header("Referer", "https://www.youtube.com/").header("Accept-Language", "fr-FR,fr;q=0.9").build()
        http.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${body.take(240).replace(Regex("\\s+"), " ")}")
            return body
        }
    }

    private fun extractInitialData(html: String): JSONObject {
        val marker = Regex("(?:var\\s+)?ytInitialData\\s*=\\s*").find(html)
            ?: Regex("\"ytInitialData\"\\s*:").find(html)
            ?: throw IOException("ytInitialData absente du HTML (HTTP 200, ${html.take(180).replace(Regex("\\s+"), " ")})")
        val start = html.indexOf('{', marker.range.last + 1)
        if (start < 0) throw IOException("objet ytInitialData absent")
        var depth = 0; var quoted = false; var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return JSONObject(html.substring(start, i + 1)) } }
        }
        throw IOException("ytInitialData tronqué")
    }

    /** Crée une playlist privée par défaut (visible dans le compte connecté). */
    fun createPlaylist(title: String, videoId: String? = null): AccountPlaylist {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank()) { "Saisis un nom de playlist" }
        val body = JSONObject().put("context", clientContext()).put("title", cleanTitle).put("privacyStatus", "PRIVATE")
        if (!videoId.isNullOrBlank()) body.put("videoIds", JSONArray().put(videoId))
        val response = requestYouTubei("playlist/create", body)
        val id = firstString(response, "playlistId") ?: throw IOException("YouTube n’a pas renvoyé l’identifiant de la playlist")
        return AccountPlaylist("VL$id", cleanTitle, "")
    }

    /** Ajoute une vidéo à une playlist YouTube Music existante via InnerTube. */
    fun addToPlaylist(playlistId: String, videoId: String) {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Identifiant vidéo YouTube invalide (11 caractères attendus)" }
        val id = playlistId.trim().removePrefix("VL")
        require(id.matches(Regex("PL[A-Za-z0-9_-]+"))) { "Identifiant de playlist YouTube invalide" }
        val action = JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId)
        val body = JSONObject().put("context", contextFor("WEB")).put("playlistId", id)
            .put("actions", JSONArray().put(action))
        // L'édition passe par browse/edit_playlist sur www.youtube.com (et non playlist/edit).
        requestYouTubeiForOrigin("browse/edit_playlist", body, "https://www.youtube.com")
    }

    fun history(): List<MediaItem> {
        val failures = mutableListOf<String>()
        // Priorité au browseId historique qui fonctionnait dans l’implémentation antérieure.
        // Pagination conservée afin de récupérer les suites lorsque YouTube en fournit.
        val attempts = listOf(
            Triple("FEhistory", "WEB", 8 * 1024 * 1024),
            Triple("FEmusic_history", "WEB", 8 * 1024 * 1024),
            Triple("FEhistory", "WEB_REMIX", 4 * 1024 * 1024),
            Triple("FEmusic_history", "WEB_REMIX", 4 * 1024 * 1024),
        )
        for ((browseId, client, maxBytes) in attempts) {
            val pages = runCatching {
                browseAllPagesClient(browseId, client, maxPages = 5, maxResponseBytes = maxBytes)
            }.onFailure { failures += "$browseId/$client: ${it.message ?: it.javaClass.simpleName}" }.getOrNull() ?: continue
            val tracks = pages.flatMap(::parseTracks).distinctBy { it.mediaId }.take(200)
            if (tracks.isNotEmpty()) return tracks
            failures += "$browseId/$client: ${pages.firstOrNull()?.let(::responseDiagnostic) ?: "réponse JSON vide"}; aucun titre exploitable"
        }
        throw IOException("Historique YouTube indisponible; ${failures.joinToString("; ").take(1400)}")
    }

    data class DownloadAudioStream(val url: String, val mimeType: String)

    /** Renvoie le flux source audio et son MIME (MP4/M4A ou WebM/Opus), sans conversion. */
    fun resolveDownloadAudioStream(pageUrl: String, highQuality: Boolean): DownloadAudioStream {
        val videoId = Regex("(?:v=|youtu\\.be/)([A-Za-z0-9_-]{6,})").find(pageUrl)?.groupValues?.get(1)
            ?: throw IOException("Identifiant vidéo YouTube invalide")
        val clients = listOf("ANDROID_VR", "IOS", "TVHTML5", "TV_EMBEDDED", "ANDROID_MUSIC", "WEB_REMIX")
        val errors = mutableListOf<String>()
        for (client in clients) {
            try {
                val body = JSONObject().put("context", contextFor(client)).put("videoId", videoId)
                    .put("contentCheckOk", true).put("racyCheckOk", true)
                val response = requestYouTubeiForOrigin("player", body, if (client == "WEB_REMIX" || client == "ANDROID_MUSIC") ORIGIN else "https://www.youtube.com")
                val status = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                if (status.isNotBlank() && status != "OK") throw IOException(status)
                val formats = response.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: JSONArray()
                val audio = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
                    .filter { it.optString("mimeType").substringBefore(';').trim().startsWith("audio/", ignoreCase = true) && it.optString("url").startsWith("https://") }
                val target = if (highQuality) 160_000 else 48_000
                val chosen = audio.minWithOrNull(compareBy<JSONObject>({ kotlin.math.abs(it.optInt("bitrate") - target) }, { it.optInt("bitrate") }))
                val url = chosen?.optString("url")?.takeIf { it.isNotBlank() } ?: throw IOException("aucun format audio direct")
                if (StreamSources.validateAudioUrl(url, userAgentFor(client))) return DownloadAudioStream(url, chosen.optString("mimeType").substringBefore(';').trim().lowercase())
                throw IOException("URL audio non accessible")
            } catch (e: Exception) { errors += "$client: ${e.message ?: e.javaClass.simpleName}" }
        }
        throw IOException("Aucun flux InnerTube valide (${errors.joinToString("; ").take(400)})")
    }

    /** Compatibilité avec les lecteurs qui nécessitent seulement une URL. */
    fun resolveAudioUrl(pageUrl: String, highQuality: Boolean): String = resolveDownloadAudioStream(pageUrl, highQuality).url

    private fun userAgentFor(client: String): String = when (client) {
        "ANDROID_VR", "ANDROID_MUSIC", "ANDROID" -> "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip"
        "IOS" -> "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_3 like Mac OS X)"
        "TVHTML5", "TV_EMBEDDED", "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        else -> UA
    }

    fun likedSongs(): List<MediaItem> {
        val failures = mutableListOf<String>()
        // Les identifiants de la liste « Titres aimés » diffèrent selon le client et
        // les versions de l'API : essayer l'agrégation puis les browseId directs.
        for (id in listOf("VLLM", "VLLL", "FEplaylist_aggregation")) {
            val pages = runCatching { browseAllPages(id, params = if (id == "FEplaylist_aggregation") "" else null) }
                .onFailure { failures += "$id: ${it.message ?: it.javaClass.simpleName}" }.getOrNull() ?: continue
            val tracks = pages.flatMap(::parseTracks).distinctBy { it.mediaId }
            if (tracks.isNotEmpty()) return tracks
            failures += "$id: ${pages.firstOrNull()?.let(::responseDiagnostic) ?: "réponse JSON vide"}; aucune vidéo extraite"
        }
        throw IOException("Titres aimés indisponibles — ${failures.joinToString("; ").take(1000)}")
    }
    fun playlistTracks(browseId: String): List<MediaItem> {
        val unique = LinkedHashMap<String, MediaItem>()
        browseAllPages(browseId).flatMap(::parseTracks).forEach { unique.putIfAbsent(it.mediaId, it) }
        return unique.values.toList()
    }
    fun artistTracks(browseId: String): List<MediaItem> = browseAllPages(browseId, maxPages = 8).flatMap(::parseTracks).distinctBy { it.mediaId }

    class Home(val tracks: List<MediaItem>)

    /** Accueil YouTube Music : uniquement les vidéos et titres proposés, jamais les playlists. */
    fun home(): Home {
        val errors = mutableListOf<String>()
        val homeAttempts = listOf(
            "FEmusic_home" to "WEB_REMIX",
            "FEwhat_to_watch" to "WEB_REMIX",
            "FEwhat_to_watch" to "WEB",
            "FEmusic_home" to "WEB",
            "FEmusic_library_landing" to "WEB_REMIX",
        )
        for ((browseId, client) in homeAttempts) {
            val pages = runCatching {
                if (client == "WEB_REMIX") browseAllPages(browseId, maxPages = 8)
                else browseAllPagesClient(browseId, client, maxPages = 8)
            }.onFailure { errors += "$browseId/$client: ${it.message ?: it.javaClass.simpleName}" }.getOrNull() ?: continue
            val tracks = LinkedHashMap<String, MediaItem>()
            pages.flatMap(::parseTracks).forEach { tracks.putIfAbsent(it.mediaId, it) }
            if (tracks.isNotEmpty()) return Home(tracks.values.toList())
            errors += "$browseId/$client: ${pages.firstOrNull()?.let(::responseDiagnostic) ?: "réponse JSON vide"}; aucun renderer vidéo exploitable"
        }
        throw IOException("Accueil YouTube indisponible — ${errors.joinToString("; ").take(1000)}")
    }

    /**
     * Récupère toutes les playlists enregistrées, pas uniquement la première page.
     * YouTube Music renvoie généralement une trentaine d'éléments par page et
     * fournit ensuite un jeton de continuation dans la réponse.
     */
    fun playlists(): List<AccountPlaylist> {
        val unique = LinkedHashMap<String, AccountPlaylist>()
        val failures = mutableListOf<String>()
        for (browseId in listOf("FEmusic_liked_playlists", "FEplaylist_aggregation", "VLLL")) {
            val pages = runCatching { browseAllPages(browseId) }
                .onFailure { failures += "$browseId: ${it.message ?: it.javaClass.simpleName}" }.getOrNull() ?: continue
            pages.forEach { page ->
                fun visit(node: Any?) {
                    when (node) {
                        is JSONObject -> {
                            val rawId = node.optString("playlistId").takeIf { it.isNotBlank() }
                                ?: node.optString("browseId").takeIf { it.startsWith("VL") || it.startsWith("PL") }
                                ?: firstString(node.optJSONObject("navigationEndpoint"), "browseId")?.takeIf { it.startsWith("VL") || it.startsWith("PL") }
                            if (rawId != null) {
                                val id = if (rawId.startsWith("VL") || rawId.startsWith("PL")) rawId else "VL$rawId"
                                val title = displayText(node.opt("title"))
                                    .ifBlank { displayText(node.opt("headline")) }
                                    .ifBlank { displayText(node.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")?.opt("title")) }
                                if (title.isNotBlank()) unique.putIfAbsent(id, AccountPlaylist(id, title, displayText(node.opt("subtitle"))))
                            }
                            val keys = node.keys()
                            while (keys.hasNext()) visit(node.opt(keys.next()))
                        }
                        is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
                    }
                }
                visit(page)
            }
            if (unique.isNotEmpty()) return unique.values.toList()
            failures += "$browseId: ${pages.firstOrNull()?.let(::responseDiagnostic) ?: "réponse JSON vide"}; aucune playlist extraite"
        }
        if (failures.isNotEmpty()) throw IOException("Playlists YouTube indisponibles — ${failures.joinToString("; ").take(1000)}")
        return unique.values.toList()
    }

    // ---------- requête ----------

    private fun searchChannelUploads(channelId: String, client: String): JSONObject {
        val body = JSONObject().put("context", contextFor(client))
            .put("query", "channel:$channelId")
        return requestYouTubeiForOrigin("search", body,
            if (client in setOf("WEB", "ANDROID", "TVHTML5")) "https://www.youtube.com" else ORIGIN)
    }

    /** Parse le flux RSS public de la chaîne (YouTube en limite habituellement la liste à 15). */
    private fun uploadsFromRss(channelId: String): List<MediaItem> {
        val request = Request.Builder().url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
            .get().header("User-Agent", UA).header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8").build()
        val xml = http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${text.take(200)}")
            text
        }
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        val result = LinkedHashMap<String, MediaItem>()
        var event = parser.eventType
        var videoId: String? = null
        var title: String? = null
        var thumbnail: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':').orEmpty()
            if (event == XmlPullParser.START_TAG && tag == "entry") {
                videoId = null; title = null; thumbnail = null
            } else if (event == XmlPullParser.START_TAG) {
                when (tag) {
                    "videoId" -> videoId = parser.nextText().trim()
                    "title" -> title = parser.nextText().trim()
                    "thumbnail" -> thumbnail = parser.getAttributeValue(null, "url")
                }
            } else if (event == XmlPullParser.END_TAG && tag == "entry") {
                val id = videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                if (id != null) result.putIfAbsent(id, uploadMediaItem(id, title?.takeIf { it.isNotBlank() } ?: "YouTube video", thumbnail, JSONObject()))
            }
            event = parser.next()
        }
        return result.values.toList()
    }

    /** Extraction défensive uploads: tout JSON, sans filtre de durée/contentType/renderer. */
    private fun robustUploadTracks(root: JSONObject): List<MediaItem> {
        val found = LinkedHashMap<String, MediaItem>()
        val idPattern = Regex("[A-Za-z0-9_-]{11}")
        fun candidateIds(node: JSONObject, isLockup: Boolean): List<String> {
            val ids = LinkedHashSet<String>()
            node.optString("videoId").takeIf { idPattern.matches(it) }?.let(ids::add)
            if (isLockup) node.optString("contentId").takeIf { idPattern.matches(it) }?.let(ids::add)
            val command = node.optJSONObject("rendererContext")?.optJSONObject("commandContext")
                ?.optJSONObject("onTap")?.optJSONObject("innertubeCommand")?.optJSONObject("watchEndpoint")
            command?.optString("videoId")?.takeIf { idPattern.matches(it) }?.let(ids::add)
            // Certains lockups n'ont pas rendererContext à leur racine.
            if (isLockup) firstString(node, "videoId")?.takeIf { idPattern.matches(it) }?.let(ids::add)
            return ids.toList()
        }
        fun titleNear(node: JSONObject): String {
            val preferred = displayText(node.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")?.opt("title"))
                .ifBlank { rendererTitle(node) }
            if (preferred.isNotBlank()) return preferred
            fun findText(value: Any?, depth: Int = 0): String? {
                if (depth > 30) return null
                when (value) {
                    is JSONObject -> {
                        for (key in listOf("title", "content", "text", "simpleText")) {
                            val v = value.opt(key)
                            if (v is String && v.isNotBlank()) return v
                            if (v is JSONObject || v is JSONArray) displayText(v).takeIf { it.isNotBlank() }?.let { return it }
                        }
                        val keys = value.keys()
                        while (keys.hasNext()) findText(value.opt(keys.next()), depth + 1)?.let { return it }
                    }
                    is JSONArray -> for (i in 0 until value.length()) findText(value.opt(i), depth + 1)?.let { return it }
                }
                return null
            }
            return findText(node) ?: "YouTube video"
        }
        fun walk(value: Any?, lockup: Boolean = false) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next(); val child = value.opt(key)
                        if (child is JSONObject && key == "lockupViewModel") {
                            candidateIds(child, true).forEach { id ->
                                val item = uploadMediaItem(id, titleNear(child), lockupThumbnail(child) ?: rendererThumbnail(child), child)
                                if (YoutubeMusicFilter.isMusic(item)) found.putIfAbsent(id, item)
                            }
                        }
                    }
                    candidateIds(value, lockup).forEach { id ->
                        val item = uploadMediaItem(id, titleNear(value), lockupThumbnail(value) ?: rendererThumbnail(value), value)
                        if (YoutubeMusicFilter.isMusic(item)) found.putIfAbsent(id, item)
                    }
                    val again = value.keys()
                    while (again.hasNext()) walk(value.opt(again.next()), lockup)
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), lockup)
            }
        }
        walk(root)
        return found.values.toList()
    }

    private fun uploadMediaItem(videoId: String, title: String, thumbnail: String?, renderer: JSONObject): MediaItem {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val image = thumbnail?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        return MediaItem.Builder().setMediaId(StreamSources.ID_PREFIX + url).setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title.ifBlank { videoId }).setArtist("YouTube")
                .setArtworkUri(YouTubeArtwork.resolve(StreamSources.ID_PREFIX + url, url, Uri.parse(image)))
                .setExtras(Bundle().apply {
                    rendererDuration(renderer)?.let { putLong("youtube_duration_seconds", parseDurationSeconds(it)) }
                    putString("youtube_music_video_type", musicVideoType(renderer).orEmpty())
                    putBoolean("youtube_music_renderer", musicVideoType(renderer) in setOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC"))
                    putBoolean("youtube_live", isLiveOrMissingDuration(renderer))
                    rendererPlaylistId(renderer)?.let { putString("youtube_playlist_id", it) }
                    putString("youtube_video_id", videoId)
                }).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
    }

    private fun clientVersion(): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "1.${f.format(Date())}.01.00"
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun browse(browseId: String, params: String? = null): JSONObject = requestBrowse(browseId = browseId, params = params)

    /** Parcourt les pages successives en évitant les jetons répétés ou cycliques. */
    private fun browseAllPages(browseId: String, maxPages: Int = 30, params: String? = null): List<JSONObject> {
        val pages = mutableListOf<JSONObject>()
        val seenTokens = mutableSetOf<String>()
        var page = browse(browseId, params)
        pages += page
        var extraPages = 0
        while (extraPages < maxPages) {
            val token = continuationToken(page) ?: break
            if (!seenTokens.add(token)) break
            page = requestBrowse(continuation = token)
            pages += page
            extraPages++
        }
        return pages
    }

    /**
     * Le jeton peut se trouver dans nextContinuationData ou dans
     * continuationEndpoint.continuationCommand selon le type de réponse YouTube.
     */
    private fun continuationToken(root: JSONObject): String? {
        fun find(node: Any?): String? {
            when (node) {
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = node.opt(key)
                        if (key == "nextContinuationData" && value is JSONObject) {
                            value.optString("continuation").takeIf { it.isNotBlank() }?.let { return it }
                        }
                        if (key == "continuationCommand" && value is JSONObject) {
                            value.optString("token").takeIf { it.isNotBlank() }?.let { return it }
                        }
                        // Continuations inside continuationItemRenderer, including those
                        // returned by appendContinuationItemsAction/reloadContinuationItemsCommand.
                        if (key == "continuationEndpoint" && value is JSONObject) {
                            value.optJSONObject("continuationCommand")?.optString("token")
                                ?.takeIf { it.isNotBlank() }?.let { return it }
                        }
                        find(value)?.let { return it }
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) find(node.opt(i))?.let { return it }
            }
            return null
        }
        return find(root)
    }

    private fun clientContext(): JSONObject = JSONObject().put("client", JSONObject()
        .put("clientName", "WEB_REMIX")
        .put("clientVersion", clientVersion())
        .put("hl", "fr").put("gl", "FR"))

    private fun webClientVersion(): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "2.${f.format(Date())}.01.00"
    }

    private fun contextFor(client: String): JSONObject {
        val (name, version) = when (client) {
            "WEB" -> "WEB" to webClientVersion()
            "ANDROID_MUSIC" -> "ANDROID_MUSIC" to "7.06.53"
            "ANDROID" -> "ANDROID" to "20.10.38"
            "WEB_REMOTE" -> "WEB_REMOTE" to webClientVersion()
            "ANDROID_VR" -> "ANDROID_VR" to "1.60.19"
            "IOS" -> "IOS" to "19.09.3"
            "TVHTML5" -> "TVHTML5" to "7.20250219.16.00"
            "TV_EMBEDDED" -> "TVHTML5_SIMPLY_EMBEDDED_PLAYER" to "2.0"
            else -> "WEB_REMIX" to clientVersion()
        }
        return JSONObject().put("client", JSONObject().put("clientName", name).put("clientVersion", version).put("hl", "fr").put("gl", "FR"))
    }

    private fun requestBrowseClient(browseId: String, client: String, params: String? = null): JSONObject =
        requestYouTubeiForOrigin("browse", JSONObject().put("context", contextFor(client)).put("browseId", browseId).apply { if (params != null) put("params", params) }, if (client in setOf("WEB", "WEB_REMOTE", "ANDROID", "TVHTML5")) "https://www.youtube.com" else ORIGIN)

    private fun requestBrowseClientLimited(browseId: String, client: String, maxBytes: Int): JSONObject =
        requestYouTubeiForOrigin("browse", JSONObject().put("context", contextFor(client)).put("browseId", browseId), if (client in setOf("WEB", "WEB_REMOTE", "ANDROID", "TVHTML5")) "https://www.youtube.com" else ORIGIN, maxBytes)

    private fun browseAllPagesClient(browseId: String, client: String, maxPages: Int = 30, params: String? = null, maxResponseBytes: Int? = null): List<JSONObject> {
        val origin = if (client in setOf("WEB", "WEB_REMOTE", "ANDROID", "TVHTML5")) "https://www.youtube.com" else ORIGIN
        val pages = mutableListOf<JSONObject>()
        var page = if (maxResponseBytes == null) requestBrowseClient(browseId, client, params) else requestBrowseClientLimited(browseId, client, maxResponseBytes)
        pages += page
        val seen = mutableSetOf<String>()
        repeat(maxPages) {
            val token = continuationToken(page) ?: return pages
            if (!seen.add(token)) return pages
            page = if (maxResponseBytes == null) requestYouTubeiForOrigin("browse", JSONObject().put("context", contextFor(client)).put("continuation", token), origin)
                else requestYouTubeiForOrigin("browse", JSONObject().put("context", contextFor(client)).put("continuation", token), origin, maxResponseBytes)
            pages += page
        }
        return pages
    }

    private fun requestBrowse(browseId: String? = null, continuation: String? = null, params: String? = null): JSONObject {
        require((browseId != null) xor (continuation != null)) { "Fournir un browseId ou un jeton de continuation" }
        val body = JSONObject().put("context", clientContext())
        if (browseId != null) body.put("browseId", browseId)
        if (params != null) body.put("params", params)
        if (continuation != null) body.put("continuation", continuation)
        return requestYouTubei("browse", body)
    }

    /** Consulte le menu du compte connecté avec les mêmes cookies et l'authentification existante. */
    private fun requestAccountMenu(): JSONObject = requestYouTubei("account/account_menu", JSONObject().put("context", clientContext()))

    /** Couche HTTP commune : aucune session ou configuration client alternative n'est créée. */
    private fun requestYouTubei(endpoint: String, body: JSONObject): JSONObject = requestYouTubeiForOrigin(endpoint, body, ORIGIN)

    private fun requestYouTubeiForOrigin(endpoint: String, body: JSONObject, origin: String, maxResponseBytes: Int? = null): JSONObject {
        val cookie = YtAuth.cookie ?: throw YtAuthException("Non connecté à YouTube Music")
        val sapisid = Regex("(?:^|;\\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)").find(cookie)?.groupValues?.get(1)
            ?: throw YtAuthException("Session YouTube incomplète, reconnecte-toi")
        val ts = System.currentTimeMillis() / 1000
        val request = Request.Builder()
            .url("$origin/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", userAgentFor(body.optJSONObject("context")?.optJSONObject("client")?.optString("clientName").orEmpty()))
            .header("Cookie", cookie)
            .header("Authorization", "SAPISIDHASH ${ts}_${sha1("$ts $sapisid $origin")}")
            .header("X-Goog-AuthUser", "0")
            .header("Origin", origin)
            .header("X-Origin", origin)
            .header("Referer", "$origin/")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .build()
        http.newCall(request).execute().use { r ->
            val responseText = if (maxResponseBytes == null) r.body?.string().orEmpty() else {
                val stream = r.body?.byteStream() ?: throw IOException("HTTP ${r.code}: réponse vide")
                val output = java.io.ByteArrayOutputStream(minOf(maxResponseBytes, 64 * 1024))
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxResponseBytes) throw IOException("Réponse historique supérieure à ${maxResponseBytes / (1024 * 1024)} Mio; abandon sécurisé")
                    output.write(buffer, 0, read)
                }
                output.toString("UTF-8")
            }
            if (r.code == 401 || r.code == 403) throw YtAuthException("HTTP ${r.code}: session YouTube expirée ou refusée; réponse: ${responseText.take(240).replace(Regex("\\s+"), " ")}")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${responseText.take(300).replace(Regex("\\s+"), " ")}")
            if (responseText.isBlank()) throw IOException("HTTP ${r.code}: réponse vide")
            return try { JSONObject(responseText) } catch (e: Exception) {
                throw IOException("HTTP ${r.code}: JSON invalide, début=${responseText.take(220).replace(Regex("\\s+"), " ")}", e)
            }
        }
    }

    /** Retourne (browseId, params) de l'onglet « Vidéos » déclaré dans la page chaîne. */
    private fun findVideoTab(root: JSONObject): Pair<String, String?>? {
        fun visit(node: Any?): Pair<String, String?>? {
            when (node) {
                is JSONObject -> {
                    val tab = node.optJSONObject("tabRenderer")
                    if (tab != null) {
                        val title = displayText(tab.opt("title")).trim()
                        val endpoint = tab.optJSONObject("endpoint") ?: tab.optJSONObject("navigationEndpoint")
                        val browse = endpoint?.optJSONObject("browseEndpoint")
                        val params = browse?.optString("params")?.takeIf { it.isNotBlank() }
                        val isVideos = title.equals("Vidéos", true) || title.equals("Videos", true) ||
                            params == "EgZ2aWRlb3PyBgQKAjoA"
                        val id = browse?.optString("browseId")?.takeIf { it.isNotBlank() }
                        if (isVideos && id != null) return id to params
                    }
                    val it = node.keys()
                    while (it.hasNext()) visit(node.opt(it.next()))?.let { found -> return found }
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))?.let { return it }
            }
            return null
        }
        return visit(root)
    }

    private fun channelIdFromAccountMenu(root: JSONObject): String? {
        // Priorité à l'en-tête du compte actif, puis au lien « Votre chaîne » du menu.
        collect(root, "activeAccountHeaderRenderer").firstNotNullOfOrNull(::findChannelId)?.let { return it }
        collect(root, "compactLinkRenderer").firstOrNull {
            runsText(it.optJSONObject("title")).equals("Votre chaîne", ignoreCase = true) ||
                runsText(it.optJSONObject("title")).equals("Your channel", ignoreCase = true)
        }?.let { findChannelId(it)?.let { id -> return id } }
        // Ne pas prendre un channelId quelconque du menu (créateurs et recommandations
        // peuvent également en contenir); seul l’en-tête ou le lien de la chaîne du compte est fiable.
        return null
    }

    private fun channelProfileFromAccountMenu(root: JSONObject): String? {
        val links = collect(root, "compactLinkRenderer")
        val link = links.firstOrNull {
            val title = displayText(it.opt("title"))
            title.equals("Votre chaîne", ignoreCase = true) || title.equals("Your channel", ignoreCase = true)
        } ?: return null
        val id = firstString(link, "browseId")?.takeIf { it.startsWith("UC") }
        if (id != null) return "https://www.youtube.com/channel/$id"
        val path = firstString(link, "canonicalBaseUrl") ?: firstString(link, "url")
            ?: firstString(link, "webCommandMetadata") ?: return null
        return when {
            path.startsWith("https://") -> path
            path.startsWith("/") -> "https://www.youtube.com$path"
            else -> null
        }
    }

    private fun findChannelId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                for (key in listOf("channelId", "browseId")) {
                    val value = node.optString(key).takeIf { it.startsWith("UC") && it.length > 2 }
                    if (value != null) return value
                }
                val keys = node.keys()
                while (keys.hasNext()) findChannelId(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findChannelId(node.opt(i))?.let { return it }
            is String -> Regex("(?:^|[^A-Za-z0-9_-])(UC[A-Za-z0-9_-]{20,})(?:$|[^A-Za-z0-9_-])")
                .find(node)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }


    // ---------- lecture des réponses ----------

    private fun displayText(node: Any?): String {
        when (node) {
            is String -> return node
            is JSONObject -> {
                node.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
                node.optString("content").takeIf { it.isNotBlank() }?.let { return it }
                node.optString("text").takeIf { it.isNotBlank() }?.let { return it }
                node.optJSONObject("accessibilityData")?.optString("label")?.takeIf { it.isNotBlank() }?.let { return it }
                node.optJSONArray("runs")?.let { runs ->
                    val text = (0 until runs.length()).joinToString("") { displayText(runs.opt(it)) }
                    if (text.isNotBlank()) return text
                }
                for (key in listOf("title", "metadata", "lockupMetadataViewModel")) {
                    val value = displayText(node.opt(key))
                    if (value.isNotBlank()) return value
                }
            }
            is JSONArray -> return (0 until node.length()).joinToString("") { displayText(node.opt(it)) }
        }
        return ""
    }

    private fun responseDiagnostic(root: JSONObject): String {
        val top = root.keys().asSequence().take(18).toList()
        val rendererKeys = mutableListOf<String>()
        fun scan(node: Any?, depth: Int = 0) {
            if (depth > 32 || rendererKeys.size >= 48) return
            when (node) {
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.endsWith("Renderer") || key == "lockupViewModel" || key == "shortsLockupViewModel" ||
                            key.contains("Continuation", true) || key == "appendContinuationItemsAction" ||
                            key == "reloadContinuationItemsCommand") rendererKeys += key
                        scan(node.opt(key), depth + 1)
                    }
                }
                is JSONArray -> for (i in 0 until minOf(node.length(), 80)) scan(node.opt(i), depth + 1)
            }
        }
        scan(root)
        val lockupExcerpt = firstLockupExcerpt(root)
        val lockupDiagnostic = lockupExcerpt?.let { "; premier lockupViewModel brut (300 caractères max)=$it" }
            ?: "; aucun lockupViewModel brut trouvé"
        return "HTTP 200; clés=${top.joinToString(",")}; clés renderer=${rendererKeys.distinct().joinToString(",").ifBlank { "aucune" }}$lockupDiagnostic"
    }

    /** Extrait le premier modèle lockup brut pour rendre les erreurs uploads diagnostiquables. */
    private fun firstLockupExcerpt(root: JSONObject): String? {
        fun find(node: Any?, depth: Int = 0): String? {
            if (depth > 40) return null
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("lockupViewModel")?.let { model ->
                        return model.toString().replace(Regex("\\s+"), " ").take(300)
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) find(node.opt(keys.next()), depth + 1)?.let { return it }
                }
                is JSONArray -> for (i in 0 until node.length()) find(node.opt(i), depth + 1)?.let { return it }
            }
            return null
        }
        return find(root)
    }

    private fun parseTracks(root: JSONObject): List<MediaItem> {
        val found = LinkedHashMap<String, MediaItem>()
        val rootPlaylistId = firstString(root, "playlistId")
        fun addMusic(item: MediaItem?) {
            if (item == null) return
            val extras = item.mediaMetadata.extras
            val title = item.mediaMetadata.title?.toString().orEmpty()
            val duration = extras?.getLong("youtube_duration_seconds", -1L) ?: -1L
            val type = extras?.getString("youtube_music_video_type")
            val live = extras?.getBoolean("youtube_live", false) == true
            val id = rootPlaylistId ?: extras?.getString("youtube_playlist_id")
                ?: extras?.getString("youtube_video_id") ?: item.mediaId
            val musicType = type ?: if (extras?.getBoolean("youtube_music_renderer", false) == true) "MUSIC_VIDEO_TYPE_ATV" else null
            val structuredMusic = musicType in setOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC") || extras?.getBoolean("youtube_music_renderer", false) == true
            if ((structuredMusic && title.isNotBlank() && !live) || YoutubeMusicFilter.isMusic(title, duration, live, musicType, id)) found.putIfAbsent(item.mediaId, item)
        }
        parseMusicTracks(root).forEach(::addMusic)
        val rendererNames = listOf("videoRenderer", "playlistVideoRenderer", "gridVideoRenderer", "lockupViewModel", "musicPlaylistPanelVideoRenderer", "playlistPanelVideoRenderer", "playlistPanelVideoWrapperRenderer", "compactVideoRenderer", "reelItemRenderer", "autoplayVideoRenderer", "richItemRenderer", "richItemRendererContent", "gridPlaylistRenderer", "tileRenderer", "musicTwoRowItemRenderer", "musicResponsiveListItemRenderer")
        // Inspecter chaque objet, pas seulement le wrapper nommé par YouTube : les
        // playlistPanelVideoWrapperRenderer/lockupViewModel peuvent imbriquer le vrai
        // videoRenderer plusieurs niveaux plus bas.
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("videoId")) addMusic(genericVideoItem(node))
                    addMusic(lockupVideoItem(node))
                    val keys = node.keys()
                    while (keys.hasNext()) visit(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
            }
        }
        // Conserver la liste documentée des structures ciblées (ainsi que le parcours
        // générique ci-dessous pour les nouveaux wrappers introduits par YouTube).
        rendererNames.flatMap { name -> collect(root, name).map { name to it } }.forEach { (name, renderer) ->
            addMusic(genericVideoItem(renderer))
            addMusic(lockupVideoItem(renderer, isLockupViewModel = name == "lockupViewModel"))
        }
        visit(root)
        return found.values.toList()
    }

    private fun rendererArtist(renderer: JSONObject): String {
        val direct = sequenceOf("ownerText", "shortBylineText", "longBylineText", "subtitle", "byline")
            .map { displayText(renderer.opt(it)) }.firstOrNull { cleanArtist(it).isNotBlank() }
        if (!direct.isNullOrBlank()) return cleanArtist(direct)
        val columns = renderer.optJSONArray("flexColumns")
        if (columns != null && columns.length() > 1) {
            val value = displayText(columns.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.opt("text"))
            if (cleanArtist(value).isNotBlank()) return cleanArtist(value)
        }
        for (key in listOf("metadata", "content", "lockupViewModel")) {
            val nested = renderer.optJSONObject(key) ?: continue
            for (field in listOf("subtitle", "byline", "ownerText", "shortBylineText", "longBylineText", "artist")) {
                val value = displayText(nested.opt(field))
                if (cleanArtist(value).isNotBlank()) return cleanArtist(value)
            }
        }
        return "YouTube"
    }

    private fun genericVideoItem(renderer: JSONObject): MediaItem? {
        // videoId/title/thumbnail peuvent être répartis entre le renderer et ses
        // sous-objets (richItem, tile, responsive list, etc.). La durée est facultative.
        if (isLiveOrMissingDuration(renderer)) return null
        val videoId = firstString(renderer, "videoId")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return null
        val title = rendererTitle(renderer).ifBlank { return null }
        val owner = rendererArtist(renderer)
        val url = "https://www.youtube.com/watch?v=$videoId"
        val thumb = rendererThumbnail(renderer) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        val ownerNode = renderer.optJSONObject("ownerText") ?: renderer.optJSONObject("shortBylineText") ?: renderer.optJSONObject("longBylineText") ?: renderer.optJSONObject("subtitle")
        val ownerBrowseId = firstString(ownerNode, "browseId") ?: firstString(renderer.optJSONArray("flexColumns")?.optJSONObject(1), "browseId")
        val ownerProfileUrl = ownerBrowseId?.let { if (it.startsWith("UC")) "https://www.youtube.com/channel/$it" else "https://music.youtube.com/browse/$it" }
        return MediaItem.Builder().setMediaId(StreamSources.ID_PREFIX + url).setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(owner)
                .setArtworkUri(YouTubeArtwork.resolve(StreamSources.ID_PREFIX + url, url, thumb?.let(Uri::parse))).setExtras(Bundle().apply {
                    if (ownerBrowseId != null) putString("creator_browse_id", ownerBrowseId)
                    if (ownerProfileUrl != null) putString("creator_profile_url", ownerProfileUrl)
                    rendererDuration(renderer)?.let { putString("duration_text", it); putLong("youtube_duration_seconds", parseDurationSeconds(it)) }
                    putString("youtube_music_video_type", musicVideoType(renderer).orEmpty())
                    putBoolean("youtube_music_renderer", musicVideoType(renderer) in setOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC"))
                    putBoolean("youtube_live", isLiveOrMissingDuration(renderer))
                    rendererPlaylistId(renderer)?.let { putString("youtube_playlist_id", it) }
                    putString("youtube_video_id", videoId)
                }).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
    }

    private fun lockupVideoItem(renderer: JSONObject, isLockupViewModel: Boolean = false): MediaItem? {
        // Tout lockup avec un ID vidéo est accepté, y compris live/Shorts si YouTube
        // les présente comme vidéos: la disponibilité du flux relève du lecteur.
        // Hors d'un nœud explicitement lockup, éviter d'interpréter chaque wrapper
        // contenant un videoId. collect(root, "lockupViewModel") marque toujours
        // le modèle concerné, même s'il ne possède aucun des champs usuels.
        val lockupMetadata = renderer.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        if (!isLockupViewModel && !renderer.has("contentId") && !renderer.has("contentType") &&
            renderer.optJSONObject("rendererContext") == null && lockupMetadata == null &&
            renderer.optJSONObject("contentImage") == null) return null
        // Les lockup récents omettent parfois contentType et/ou contentId. Ne pas
        // filtrer les shorts ni les modèles incomplets: tout ID vidéo valide suffit.
        fun findVideoId(node: Any?, depth: Int = 0): String? {
            if (depth > 32) return null
            when (node) {
                is JSONObject -> {
                    node.optString("videoId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { return it }
                    val keys = node.keys()
                    while (keys.hasNext()) findVideoId(node.opt(keys.next()), depth + 1)?.let { return it }
                }
                is JSONArray -> for (i in 0 until node.length()) findVideoId(node.opt(i), depth + 1)?.let { return it }
            }
            return null
        }
        val contentId = renderer.optString("contentId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val commandVideoId = firstString(
            renderer.optJSONObject("rendererContext")?.optJSONObject("commandContext")
                ?.optJSONObject("onTap")?.optJSONObject("innertubeCommand")?.optJSONObject("watchEndpoint"),
            "videoId",
        )?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val videoId = contentId ?: commandVideoId ?: findVideoId(renderer) ?: return null
        val title = displayText(lockupMetadata?.opt("title")).ifBlank { rendererTitle(renderer) }.ifBlank { videoId }
        val url = "https://www.youtube.com/watch?v=$videoId"
        val thumb = lockupThumbnail(renderer) ?: rendererThumbnail(renderer)
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        val ownerName = lockupAuthor(renderer).ifBlank { rendererArtist(renderer) }
        val ownerBrowseId = (firstString(renderer.optJSONObject("byline"), "browseId")
            ?: firstString(lockupMetadata, "browseId")
            ?: firstString(renderer, "browseId"))?.takeIf { it.startsWith("UC") }
        val ownerProfileUrl = ownerBrowseId?.let { "https://www.youtube.com/channel/$it" }
        return MediaItem.Builder().setMediaId(StreamSources.ID_PREFIX + url).setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(ownerName)
                .setArtworkUri(YouTubeArtwork.resolve(StreamSources.ID_PREFIX + url, url, Uri.parse(thumb))).setExtras(Bundle().apply {
                    if (ownerBrowseId != null) putString("creator_browse_id", ownerBrowseId)
                    if (ownerProfileUrl != null) putString("creator_profile_url", ownerProfileUrl)
                    rendererDuration(renderer)?.let { putString("duration_text", it); putLong("youtube_duration_seconds", parseDurationSeconds(it)) }
                    putString("youtube_music_video_type", musicVideoType(renderer).orEmpty())
                    putBoolean("youtube_music_renderer", musicVideoType(renderer) in setOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC"))
                    putBoolean("youtube_live", isLiveOrMissingDuration(renderer))
                    rendererPlaylistId(renderer)?.let { putString("youtube_playlist_id", it) }
                    putString("youtube_video_id", videoId)
                }).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
    }

    /** Image dédiée au nouveau lockup : contentImage ou collectionThumbnailViewModel. */
    private fun lockupThumbnail(renderer: JSONObject): String? {
        val candidates = listOf(
            renderer.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")?.optJSONObject("image")?.opt("sources"),
            renderer.optJSONObject("collectionThumbnailViewModel")?.optJSONObject("thumbnailViewModel")?.optJSONObject("image")?.opt("sources"),
            renderer.optJSONObject("collectionThumbnailViewModel")?.opt("image"),
        )
        for (candidate in candidates) findThumbnailUrl(candidate)?.let { return it }
        return null
    }

    /** Récupère le nom du canal depuis les métadonnées lockup, sans confondre titre et auteur. */
    private fun lockupAuthor(renderer: JSONObject): String {
        val metadata = renderer.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        for (key in listOf("metadata", "subtitle", "byline", "secondaryText")) {
            val text = cleanArtist(displayText(metadata?.opt(key)))
            if (text.isNotBlank()) return text
        }
        val rowValues = mutableListOf<String>()
        fun collectMetadataParts(node: Any?, depth: Int = 0) {
            if (depth > 16) return
            when (node) {
                is JSONObject -> {
                    if (node.has("metadataParts")) {
                        val parts = node.optJSONArray("metadataParts")
                        if (parts != null) for (i in 0 until parts.length()) {
                            val text = cleanArtist(displayText(parts.optJSONObject(i)?.opt("text")))
                            if (text.isNotBlank()) rowValues += text
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) collectMetadataParts(node.opt(keys.next()), depth + 1)
                }
                is JSONArray -> for (i in 0 until node.length()) collectMetadataParts(node.opt(i), depth + 1)
            }
        }
        collectMetadataParts(metadata?.opt("metadata"))
        rowValues.firstOrNull { it.isNotBlank() }?.let { return cleanArtist(it) }
        val context = renderer.optJSONObject("rendererContext")?.optJSONObject("commandContext")
        val author = cleanArtist(displayText(context?.opt("channelName")))
        return author
    }

    private fun parseDurationSeconds(value: String): Long {
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

    /** Durée affichée par les renderers browse, conservée même si le renderer est profondément imbriqué. */
    private fun rendererDuration(node: JSONObject): String? {
        for (key in listOf("lengthText", "durationText", "lengthSeconds")) {
            val value = displayText(node.opt(key)).trim()
            if (value.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")) ||
                (key == "lengthSeconds" && value.matches(Regex("\\d+")))) return value
        }
        val candidates = listOf("thumbnailOverlayTimeStatusRenderer", "timeStatus", "overlayMetadata")
        for (key in candidates) {
            val value = firstString(node.opt(key), "text")?.trim()
            if (!value.isNullOrBlank() && value.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?"))) return value
        }
        // Nouveau lockupViewModel : durée dans les badges ou la surimpression basse.
        val durationPattern = Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")
        fun findBadgeDuration(value: Any?): String? {
            when (value) {
                is String -> return value.trim().takeIf { durationPattern.matches(it) }
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) findBadgeDuration(value.opt(keys.next()))?.let { return it }
                }
                is JSONArray -> for (i in 0 until value.length()) findBadgeDuration(value.opt(i))?.let { return it }
            }
            return null
        }
        findBadgeDuration(node.opt("thumbnailOverlayBadgeViewModel"))?.let { return it }
        findBadgeDuration(node.opt("thumbnailBottomOverlayViewModel"))?.let { return it }
        // Les nouveaux modèles peuvent placer l’overlay à l’intérieur de contentImage/overlays.
        fun findNestedOverlay(value: Any?): String? {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (key == "thumbnailBottomOverlayViewModel" || key == "thumbnailOverlayBadgeViewModel") {
                            findBadgeDuration(child)?.let { return it }
                        }
                        findNestedOverlay(child)?.let { return it }
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) findNestedOverlay(value.opt(i))?.let { return it }
            }
            return null
        }
        return findNestedOverlay(node)
    }

    /** Recherche récursive d'un titre connu, quelle que soit l'imbrication des wrappers. */
    private fun rendererTitle(node: JSONObject): String {
        val titleKeys = setOf("title", "headline", "videotitle")
        fun find(value: Any?, depth: Int = 0): String {
            if (depth > 24) return ""
            when (value) {
                is JSONObject -> {
                    // Prioriser les champs titulaires avant de descendre les objets internes.
                    for (key in listOf("title", "headline", "videoTitle", "lockupMetadataViewModel")) {
                        val text = displayText(value.opt(key))
                        if (text.isNotBlank()) return text
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.lowercase() in titleKeys) continue
                        find(value.opt(key), depth + 1).takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) find(value.opt(i), depth + 1).takeIf { it.isNotBlank() }?.let { return it }
            }
            return ""
        }
        find(node).takeIf { it.isNotBlank() }?.let { return it }
        // Les éléments Music présentent parfois le titre uniquement en colonne flexible.
        node.optJSONArray("flexColumns")?.let { columns ->
            for (i in 0 until columns.length()) {
                val text = displayText(columns.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.opt("text"))
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    /** Les miniatures existent sous thumbnail.thumbnails, thumbnailViewModel.image.sources, etc. */
    private fun rendererThumbnail(node: JSONObject): String? {
        val candidates = listOf("thumbnail", "thumbnailRenderer", "thumbnailViewModel", "image", "thumbnails")
        for (key in candidates) {
            val value = node.opt(key)
            if (value != null) firstString(value, "url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { return it }
        }
        // Une image peut être encapsulée dans metadata/lockup, sans champ thumbnail à la racine.
        for (key in listOf("metadata", "content", "lockupViewModel", "richItemRenderer", "richItemRendererContent", "videoRenderer", "gridVideoRenderer", "tileRenderer")) {
            val value = node.opt(key)
            if (value != null) {
                val found = findThumbnailUrl(value)
                if (found != null) return found
            }
        }
        // Une vignette YouTube peut être publiée uniquement comme tableau d’objets {url}.
        return findThumbnailUrl(node)
    }

    private fun findThumbnailUrl(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (key.equals("url", true) && value is String && (value.startsWith("https://") || value.startsWith("http://"))) return value
                }
                val keysAgain = node.keys()
                while (keysAgain.hasNext()) {
                    val key = keysAgain.next()
                    val value = node.opt(key)
                    if (key.contains("thumbnail", true) || key.equals("image", true) || key.contains("contentImage", true) || key.contains("imageViewModel", true) || key.equals("sources", true)) {
                        findThumbnailUrl(value)?.let { return it }
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) findThumbnailUrl(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun parseMusicTracks(root: JSONObject): List<MediaItem> =
        collect(root, "musicResponsiveListItemRenderer").mapNotNull { r ->
            val cols = r.optJSONArray("flexColumns")
            val videoId = firstString(r, "videoId")
                ?: firstString(r.optJSONObject("playlistItemData"), "videoId")
                ?: firstString(r.optJSONObject("overlay"), "videoId")
                ?: firstString(r.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer"), "videoId")
                ?: firstString(r.optJSONObject("navigationEndpoint"), "videoId")
                ?: firstString(r.optJSONObject("onTap"), "videoId")
                ?: return@mapNotNull null
            fun column(i: Int): String = displayText(cols?.optJSONObject(i)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.opt("text"))
            val title = column(0).ifBlank { rendererTitle(r) }.ifBlank { return@mapNotNull null }
            val durationText = rendererDuration(r) ?: run {
                val meta = displayText(cols?.optJSONObject(2)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.opt("text"))
                Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b").find(meta)?.value
            }
            val durationSec = durationText?.let(::parseDurationSeconds) ?: -1L
            val type = musicVideoType(r).orEmpty().ifBlank { "MUSIC_VIDEO_TYPE_ATV" }
            // Titres aimés sont déjà dans un renderer musical structuré; ne les rejeter
            // pas lorsque la durée est absente du renderer (le lecteur peut la résoudre).
            if (!YoutubeMusicFilter.isMusic(title, durationSec, isLiveOrMissingDuration(r), type, rendererPlaylistId(r) ?: videoId) && durationSec > 0L) return@mapNotNull null
            val artist = cleanArtist(column(1)).ifBlank { rendererArtist(r) }.ifBlank { "YouTube Music" }
            val channelId = firstString(cols?.optJSONObject(1), "browseId")
                ?: firstString(cols?.optJSONObject(1), "navigationEndpoint")?.takeIf { it.startsWith("UC") }
            val profileUrl = channelId?.let { if (it.startsWith("UC")) "https://music.youtube.com/channel/$it" else "https://music.youtube.com/browse/$it" }
            val url = "https://www.youtube.com/watch?v=$videoId"
            MediaItem.Builder()
                .setMediaId(StreamSources.ID_PREFIX + url)
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setArtworkUri(YouTubeArtwork.resolve(StreamSources.ID_PREFIX + url, url, rendererThumbnail(r)?.let(Uri::parse)))
                        .setExtras(Bundle().apply {
                            if (profileUrl != null) putString("creator_profile_url", profileUrl)
                            if (channelId != null) putString("creator_browse_id", channelId)
                            putBoolean("youtube_music_renderer", true)
                            putString("youtube_music_video_type", type)
                            putLong("youtube_duration_seconds", durationSec)
                            if (durationText != null) putString("duration_text", durationText)
                            putBoolean("youtube_live", false)
                            putString("youtube_video_id", videoId)
                            rendererPlaylistId(r)?.let { putString("youtube_playlist_id", it) }
                        })
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                ).build()
        }

    /** Seuls les signaux structurés de diffusion en direct sont considérés comme live. */
    private fun isLiveOrMissingDuration(node: JSONObject): Boolean {
        var live = false
        val liveWords = setOf("live", "en direct", "diffusion en direct", "direct")
        fun walk(value: Any?, key: String = "", depth: Int = 0) {
            if (live || depth > 24) return
            when (value) {
                is JSONObject -> {
                    val style = value.optString("style").uppercase()
                    val badgeStyle = value.optString("badgeStyle").uppercase()
                    val status = value.optString("status").uppercase()
                    val text = displayText(value.opt("text")).trim().lowercase()
                    if ("BADGE_STYLE_TYPE_LIVE_NOW" in style || "BADGE_STYLE_TYPE_LIVE_NOW" in badgeStyle ||
                        (key.contains("thumbnailOverlayTimeStatusRenderer") && ("LIVE" in style || text in liveWords)) ||
                        (key.contains("badge", true) && ("LIVE" in style || "LIVE" in badgeStyle || text in liveWords)) ||
                        status in setOf("LIVE", "LIVE_NOW")) live = true
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val childKey = keys.next()
                        walk(value.opt(childKey), childKey, depth + 1)
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), key, depth + 1)
                is String -> {
                    val text = value.trim().lowercase()
                    if (key.contains("badge", true) && text in setOf("live", "en direct", "diffusion en direct") ||
                        key.contains("thumbnailOverlayTimeStatusRenderer") && text in setOf("live", "en direct")) live = true
                }
            }
        }
        walk(node)
        return live
    }

    private fun rendererPlaylistId(node: JSONObject): String? =
        firstString(node, "playlistId")?.takeIf { it.startsWith("RD", ignoreCase = true) }

    private fun runsText(node: JSONObject?): String {
        val runs = node?.optJSONArray("runs") ?: return ""
        val raw = (0 until runs.length()).mapNotNull { runs.optJSONObject(it)?.optString("text") }.joinToString("")
        // Les sous-titres YouTube peuvent mêler artiste/uploadeur, durée et compteur de vues.
        // Filtrer le segment de vues seulement pour conserver les autres métadonnées.
        return cleanArtist(raw)
    }

    /** Tous les objets portant ce nom de clé, dans l'ordre d'apparition. */
    private fun collect(node: Any?, key: String, out: MutableList<JSONObject> = mutableListOf()): MutableList<JSONObject> {
        when (node) {
            is JSONObject -> for (k in node.keys()) {
                val v = node.opt(k)
                if (k == key && v is JSONObject) out += v
                collect(v, key, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
        return out
    }

    private fun firstString(node: Any?, key: String): String? {
        when (node) {
            is JSONObject -> for (k in node.keys()) {
                val v = node.opt(k)
                if (k == key && v is String) return v
                firstString(v, key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) firstString(node.opt(i), key)?.let { return it }
        }
        return null
    }
}
