package com.so.svg599.musique

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * YouTube et SoundCloud via NewPipeExtractor.
 * Les MediaItem portent l'URL de la page (watch?v=..., soundcloud.com/...).
 * Le lien audio réel est résolu au moment de la lecture par [StreamResolver] ou
 * pré-résolu en arrière-plan; les URL signées ont un cache borné par une TTL courte.
 */
object StreamSources {
    const val YOUTUBE = "YouTube"
    const val SOUNDCLOUD = "SoundCloud"
    const val ID_PREFIX = "stream:"
    /** Nombre maximal de titres dont l’URL est pré-résolue par déclenchement. */
    const val PREFETCH_TRACK_COUNT = 4
    // Les liens YouTube signés peuvent être révoqués avant leur date d'expiration.
    // Cache court, sans dépasser l'expiration déclarée dans l'URL (marge de sécurité incluse).
    private const val YOUTUBE_CACHE_MS = 10 * 60 * 1000L
    // URL SoundCloud signée : très courte durée pour exploiter le préchargement sans
    // conserver longtemps un lien dont l'expiration n'est pas toujours indiquée.
    private const val SOUNDCLOUD_CACHE_MS = 90 * 1000L

    @Volatile private var targetAudioBitrate = 160
    @Volatile private var filterNonMusicResults = true
    fun setAudioQuality(high: Boolean) { targetAudioBitrate = if (high) 160 else 50; cache.clear() }
    fun useHigherAudioQuality(): Boolean { if (targetAudioBitrate >= 160) return false; targetAudioBitrate = 160; cache.clear(); return true }
    fun audioQualityBitrate(): Int = targetAudioBitrate
    fun setFilterNonMusicResults(enabled: Boolean) { filterNonMusicResults = enabled }

    private var initDone = false
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val resolving = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val resolvingWorkers = ConcurrentHashMap<String, java.util.concurrent.Future<*>>()
    private val resolverExecutor = Executors.newFixedThreadPool(2)
    private val prefetchExecutor = Executors.newFixedThreadPool(2)
    private val prefetchJobs = ConcurrentHashMap<String, java.util.concurrent.Future<*>>()

    @Synchronized
    private fun ensureInit() {
        if (!initDone) {
            NewPipe.init(NewPipeDownloader(), Localization("fr", "FR"))
            initDone = true
        }
    }

    /**
     * Recherche paginée : NewPipe ne renvoie initialement que la première page.
     * On récupère quelques pages supplémentaires pour éviter de ne montrer que
     * 2 ou 3 titres alors que le moteur en propose beaucoup plus.
     */
    fun search(source: String, query: String): List<MediaItem> {
        ensureInit()
        val service = if (source == SOUNDCLOUD) ServiceList.SoundCloud else ServiceList.YouTube
        val queryHandler = service.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(service, queryHandler)
        val found = LinkedHashMap<String, StreamInfoItem>()

        fun collect(items: List<InfoItem>) {
            items.filterIsInstance<StreamInfoItem>().forEach { item ->
                if (item.streamType != StreamType.LIVE_STREAM &&
                    item.streamType != StreamType.AUDIO_LIVE_STREAM &&
                    item.duration > 0 && item.duration <= YoutubeMusicFilter.MAX_DURATION_SECONDS &&
                    item.url.isNotBlank()
                ) {
                    found.putIfAbsent(item.url, item)
                }
            }
        }

        collect(info.relatedItems)
        var nextPage = info.nextPage
        var pageCount = 1
        while (nextPage != null && pageCount < MAX_SEARCH_PAGES && found.size < MAX_SEARCH_RESULTS) {
            val page = try {
                SearchInfo.getMoreItems(service, queryHandler, nextPage)
            } catch (_: Exception) {
                // Garde les résultats déjà obtenus si une page suivante échoue.
                break
            }
            collect(page.items)
            nextPage = page.nextPage
            pageCount++
        }

        val converted = found.values.take(MAX_SEARCH_RESULTS).map { item ->
            MediaItem.Builder()
                .setMediaId(ID_PREFIX + item.url)
                .setUri(item.url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.name)
                        .setArtist(if (source == YOUTUBE) cleanArtist(item.uploaderName.orEmpty()).ifBlank { "YouTube" } else item.uploaderName ?: source)
                        .setArtworkUri(if (source == YOUTUBE) YouTubeArtwork.resolve(ID_PREFIX + item.url, item.url, item.thumbnails.firstOrNull()?.url?.takeIf { it.isNotBlank() }?.let(Uri::parse)) else item.thumbnails.firstOrNull()?.url?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                        .setExtras(Bundle().apply {
                            item.uploaderUrl?.takeIf { it.startsWith("https://") }?.let { putString("creator_profile_url", it) }
                            item.uploaderUrl?.takeIf { source == SOUNDCLOUD && it.startsWith("https://") }?.let { putString("creator_permalink", it) }
                            if (source == YOUTUBE) {
                                putLong("youtube_duration_seconds", item.duration.toLong())
                                putBoolean("youtube_music_renderer", false)
                                Regex("[?&]list=([^&]+)").find(item.url)?.groupValues?.get(1)?.let {
                                    putString("youtube_playlist_id", it)
                                }
                                item.uploaderUrl?.let { url ->
                                    Regex("(?:/channel/|/browse/)([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)?.let { id ->
                                        putString("creator_browse_id", id)
                                        if (url.startsWith("https://")) putString("creator_profile_url", url)
                                    }
                                }
                            }
                        })
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                ).build()
        }
        return if (source == YOUTUBE && filterNonMusicResults) converted.filter(YoutubeMusicFilter::isSearchMusic) else converted
    }

    /** Liste les morceaux/vidéos publiés sur l’accueil de chaîne, puis fallback sur Vidéos. */
    fun youtubeChannelTracks(profileUrl: String?, browseId: String?, artistName: String? = null): List<MediaItem> {
        ensureInit()
        val url = profileUrl?.takeIf { it.startsWith("https://") }
            ?: browseId?.let { if (it.startsWith("UC")) "https://www.youtube.com/channel/$it" else "https://music.youtube.com/browse/$it" }
        if (browseId != null && !browseId.startsWith("UC")) {
            val artistTracks = runCatching { YtMusicClient.artistTracks(browseId) }.getOrDefault(emptyList())
            if (artistTracks.isNotEmpty()) return artistTracks
        }
        val channel = try {
            if (url == null) throw IOException("Identifiant de chaîne YouTube indisponible")
            ChannelInfo.getInfo(ServiceList.YouTube, url)
        } catch (e: Exception) {
            val query = artistName?.takeIf { it.isNotBlank() } ?: profileUrl ?: browseId.orEmpty()
            val fallback = runCatching { search(YOUTUBE, query) }.getOrDefault(emptyList())
            if (fallback.isNotEmpty()) return fallback
            throw IOException("Impossible de résoudre la chaîne '$query' (handle, chaîne ou onglet Vidéos indisponible).", e)
        }
        val tabs = channel.tabs
        val homeTab = tabs.firstOrNull { handler ->
            handler.contentFilters.any { it.equals("HOME", ignoreCase = true) || it.equals("FEATURED", ignoreCase = true) }
        }
        val videosTab = tabs.firstOrNull { handler ->
            handler.contentFilters.any { it.equals("VIDEOS", ignoreCase = true) }
        }
        val streams = LinkedHashMap<String, StreamInfoItem>()

        fun loadTab(tab: ListLinkHandler?) {
            if (tab == null) return
            val info = ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
            fun add(items: List<InfoItem>) = items.filterIsInstance<StreamInfoItem>()
                .filter { it.url.isNotBlank() && it.duration > 0 && it.duration <= YoutubeMusicFilter.MAX_DURATION_SECONDS &&
                    it.streamType != StreamType.LIVE_STREAM && it.streamType != StreamType.AUDIO_LIVE_STREAM }
                .forEach { streams.putIfAbsent(it.url, it) }
            add(info.relatedItems)
            var nextPage = info.nextPage
            var pageCount = 1
            while (nextPage != null && pageCount < MAX_CHANNEL_PAGES && streams.size < MAX_CHANNEL_RESULTS) {
                val page = try {
                    ChannelTabInfo.getMoreItems(ServiceList.YouTube, tab, nextPage)
                } catch (_: Exception) {
                    break
                }
                add(page.items)
                nextPage = page.nextPage
                pageCount++
            }
        }

        // L’accueil peut contenir des shelves de playlists/chaînes : on ne garde que
        // StreamInfoItem, puis on déduplique par URL. Si aucun morceau/vidéo n’est
        // présent (ou si l’onglet accueil échoue), utilise l’onglet Vidéos.
        if (homeTab != null) runCatching { loadTab(homeTab) }
        if (streams.isEmpty()) {
            val fallback = videosTab
            if (fallback != null) runCatching { loadTab(fallback) }
            if (streams.isEmpty()) {
                val query = artistName?.takeIf { it.isNotBlank() } ?: channel.name
                val found = runCatching { search(YOUTUBE, query) }.getOrDefault(emptyList())
                if (found.isNotEmpty()) return found
                throw IOException("Aucun contenu vidéo accessible sur l'onglet Vidéos de cette chaîne")
            }
        }
        val channelItems = streams.values.take(MAX_CHANNEL_RESULTS).map { item ->
            MediaItem.Builder().setMediaId(ID_PREFIX + item.url).setUri(item.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).setArtist(cleanArtist(item.uploaderName.orEmpty()).ifBlank { "YouTube" })
                    .setArtworkUri(YouTubeArtwork.resolve(ID_PREFIX + item.url, item.url, item.thumbnails.firstOrNull()?.url?.takeIf { it.isNotBlank() }?.let(Uri::parse)))
                    .setExtras(Bundle().apply {
                        putString("creator_profile_url", url)
                        putString("creator_browse_id", channel.id)
                        putLong("youtube_duration_seconds", item.duration.toLong())
                        putBoolean("youtube_music_renderer", false)
                        Regex("[?&]list=([^&]+)").find(item.url)?.groupValues?.get(1)?.let { putString("youtube_playlist_id", it) }
                    }).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
        }
        return channelItems.filter(YoutubeMusicFilter::isMusic)
    }

    private const val MAX_SEARCH_PAGES = 4 // première page + jusqu'à 3 suivantes
    private const val MAX_SEARCH_RESULTS = 80
    private const val MAX_CHANNEL_PAGES = 5
    private const val MAX_CHANNEL_RESULTS = 100

    fun isStreamPage(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == "youtu.be" || host.endsWith("youtube.com") || host == "soundcloud.com" || host.endsWith(".soundcloud.com") && !host.startsWith("api")
    }

    /** Renvoie le lien audio direct, mutualise les résolutions concurrentes (lecture + préchargement). */
    fun resolveAudioUrl(pageUrl: String, preferSoundCloudHls: Boolean = false, excludedItags: Set<Int> = emptySet()): String {
        val bitrate = targetAudioBitrate
        val key = "$pageUrl|hls=$preferSoundCloudHls|bitrate=$bitrate|exclude=${excludedItags.sorted().joinToString(",")}"
        val future = resolving.computeIfAbsent(key) {
            val result = CompletableFuture<String>()
            val worker = resolverExecutor.submit {
                try { result.complete(resolveAudioUrlUncached(pageUrl, preferSoundCloudHls, bitrate, excludedItags)) }
                catch (e: Throwable) { result.completeExceptionally(e) }
            }
            resolvingWorkers[key] = worker
            result
        }
        return try {
            future.get()
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is IOException) throw cause
            throw IOException(cause.message ?: "Extraction impossible", cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Résolution audio interrompue", e)
        } finally {
            if (future.isDone) { resolving.remove(key, future); resolvingWorkers.remove(key) }
        }
    }

    /**
     * Pré-résout seulement les URL signées des quatre titres au plus (pas leurs octets).
     * L’audio effectivement lu passe ensuite par CacheDataSource dans RemotePlayback.
     */
    fun preResolveAudioUrls(pageUrls: Collection<String>) {
        pageUrls.asSequence().filter { isStreamPage(Uri.parse(it)) }.distinct().take(PREFETCH_TRACK_COUNT).forEach { page ->
            if (prefetchJobs[page]?.isDone == false) return@forEach
            prefetchJobs[page] = prefetchExecutor.submit { try { runCatching { resolveAudioUrl(page) } } finally { prefetchJobs.remove(page) } }
        }
    }

    /** Annule les résolutions et préchargements de titres sautés, sans interrompre le titre courant. */
    fun cancelPendingResolutionsExcept(currentPageUrl: String?) {
        prefetchJobs.entries.toList().forEach { (page, task) ->
            if (page != currentPageUrl && prefetchJobs.remove(page, task)) task.cancel(true)
        }
        resolving.entries.toList().forEach { (key, future) ->
            val page = key.substringBefore("|hls=")
            if (page != currentPageUrl && resolving.remove(key, future)) {
                future.cancel(true)
                resolvingWorkers.remove(key)?.cancel(true)
            }
        }
    }

    private fun resolveAudioUrlUncached(pageUrl: String, preferSoundCloudHls: Boolean, bitrate: Int, excludedItags: Set<Int>): String {
        val isSoundCloud = Uri.parse(pageUrl).host?.endsWith("soundcloud.com") == true
        val now = System.currentTimeMillis()
        if (!preferSoundCloudHls && excludedItags.isEmpty()) {
            cache[pageUrl]?.let { (url, time) ->
                val ttl = if (isSoundCloud) SOUNDCLOUD_CACHE_MS else YOUTUBE_CACHE_MS
                val expiresAt = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)
                val safetyMargin = if (isSoundCloud) 15_000L else 60_000L
                val validUntil = minOf(time + ttl, expiresAt?.minus(safetyMargin) ?: Long.MAX_VALUE)
                if (now < validUntil) return url
                cache.remove(pageUrl)
            }
        }
        val own = if (isSoundCloud) runCatching { SoundCloudClient.streamUrl(pageUrl, preferHls = preferSoundCloudHls) }.getOrNull() else null
        val url = own ?: if (isSoundCloud) viaNewPipe(pageUrl) else {
            runCatching { YtMusicClient.resolveAudioUrl(pageUrl, bitrate >= 160, excludedItags) }
                .getOrElse {
                    if (excludedItags.isNotEmpty()) throw IOException("Aucun autre flux InnerTube compatible", it)
                    val fallback = viaNewPipe(pageUrl)
                    if (!validateAudioUrl(fallback, "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"))
                        throw IOException("URL audio YouTube inaccessible après repli NewPipe", it)
                    fallback
                }
        }
        if (!preferSoundCloudHls && excludedItags.isEmpty() && (isSoundCloud || bitrate == targetAudioBitrate)) cache[pageUrl] = url to System.currentTimeMillis()
        return url
    }

    /** Validation légère de l'URL audio avant mise en cache (HEAD puis Range GET). */
    fun validateAudioUrl(url: String, userAgent: String): Boolean {
        if (!url.startsWith("https://")) return false
        fun probe(method: String): Boolean = try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = method
            connection.setRequestProperty("User-Agent", userAgent)
            if (method == "GET") connection.setRequestProperty("Range", "bytes=0-0")
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299 || code == 206
        } catch (_: Exception) { false }
        return probe("HEAD") || probe("GET")
    }

    /** Résout une URL YouTube progressive compatible vidéo, au plus près de 360p. */
    fun resolveVideo360Url(pageUrl: String): String {
        ensureInit()
        val info = StreamInfo.getInfo(pageUrl)
        val streams = info.videoStreams.filter { !it.content.isNullOrBlank() }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val chosen = streams.minByOrNull { stream ->
            val h = Regex("(\\d+)").find(stream.resolution.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
            if (h <= 360) 360 - h else 10_000 + h
        } ?: throw IOException("Aucun flux vidéo progressif YouTube disponible")
        return chosen.content
    }

    /** Itag de l'URL signée précédemment résolue, conservé pour exclure le flux fautif. */
    fun cachedAudioItag(pageUrl: String): Int? = cache[pageUrl]?.first?.let {
        Regex("[?&]itag=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Invalide une URL précédemment résolue après une erreur de lecture/réseau. */
    fun invalidate(pageUrl: String) { cache.remove(pageUrl) }

    private fun viaNewPipe(pageUrl: String): String {
        ensureInit()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val info = StreamInfo.getInfo(pageUrl)
                val streams = info.audioStreams.filter { !it.content.isNullOrBlank() }
                val available = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.ifEmpty { streams }
                val isSoundCloud = Uri.parse(pageUrl).host?.lowercase()?.let { it == "soundcloud.com" || it.endsWith(".soundcloud.com") } == true
                // À basse qualité, viser environ 50 kbit/s; NewPipe ne garantit pas le choix
                // d'un itag précis, donc prendre le débit le plus proche. En InnerTube, itag 250
                // (Opus ~50 kbit/s) est prioritaire et le débit le plus faible sert de repli.
                val target = targetAudioBitrate
                val best = if (!isSoundCloud && target <= 50) {
                    available.filter { it.averageBitrate > 0 }.minWithOrNull(compareBy(
                        { kotlin.math.abs(it.averageBitrate - 50) }, { it.averageBitrate }
                    )) ?: available.minByOrNull { it.averageBitrate }
                } else {
                    available.minWithOrNull(compareBy<org.schabi.newpipe.extractor.stream.AudioStream>(
                        { kotlin.math.abs(it.averageBitrate - (if (target >= 160) target else 160)) }, { it.averageBitrate }
                    ))
                } ?: throw IOException("Aucun flux audio lisible pour ce titre")
                return best.content
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) Thread.sleep(350)
            }
        }
        throw IOException(lastError?.message ?: "Échec de l’extraction du flux YouTube", lastError)
    }
}

/** Remplace l'URL de page par le lien audio réel au moment où ExoPlayer ouvre la source. */
object StreamResolver : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (!StreamSources.isStreamPage(dataSpec.uri)) return dataSpec
        val resolved = try {
            StreamSources.resolveAudioUrl(dataSpec.uri.toString())
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Extraction impossible", e)
        }
        return dataSpec.withUri(Uri.parse(resolved))
    }
}
