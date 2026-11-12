package com.so.svg599.musique

import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import java.util.concurrent.ConcurrentHashMap
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import android.app.Notification
import android.widget.Toast
import android.content.ContentUris
import android.provider.MediaStore
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

/**
 * Service de lecture : notification, écran verrouillé, Bluetooth et Android Auto
 * (via l'arborescence exposée dans onGetLibraryRoot / onGetChildren).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var playerListener: Player.Listener? = null
    private var statusReceiver: BroadcastReceiver? = null
    private var autoCategoryReceiver: BroadcastReceiver? = null
    private var skipSilenceReceiver: BroadcastReceiver? = null
    private val silenceHandler = Handler(Looper.getMainLooper())
    private val musicOfftopicSegments = ConcurrentHashMap<String, List<Pair<Long, Long>>>()
    private val skippedMusicOfftopicSegments = mutableSetOf<String>()
    private var activeSilenceVideoId: String? = null
    private var failedTrackQueueKey: List<String> = emptyList()
    private val failedTrackIndices = mutableSetOf<Int>()
    /** Une seule résolution de secours par tentative de piste, avant le saut automatique. */
    private val retriedStreamItems = mutableSetOf<String>()
    private val qualityFallbackItems = mutableSetOf<String>()
    private val libraryExecutor = Executors.newFixedThreadPool(2)
    private val searchExecutor = Executors.newFixedThreadPool(2)

    override fun onCreate() {
        super.onCreate()
        // Android Auto peut démarrer le service sans ouvrir MainActivity : restaure
        // les identifiants pCloud uniquement si l'utilisateur a demandé leur sauvegarde.
        runCatching {
            SecureCredentials.load(this)?.let { saved ->
                WebDavAuth.configure(saved.server, saved.email, saved.password)
            }
        }
        // Sessions YouTube Music / SoundCloud pour Android Auto (le service peut démarrer sans l'appli).
        runCatching { YtAuth.load(this); ScAuth.load(this) }
        // Media3 gère la notification multimédia et ses commandes dans le panneau.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .build()
        )
        createStatusIconChannel()
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val enabled = intent?.getBooleanExtra("enabled", true) ?: true
                if (!enabled) hideStatusIcon() else {
                    val p = session?.player as? ExoPlayer
                    if (p?.isPlaying == true) showStatusIcon(p)
                }
            }
        }
        autoCategoryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { session?.notifyChildrenChanged(ROOT, 4, null) }
        }
        skipSilenceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                (session?.player as? ExoPlayer)?.let(::updateSkipSilence)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, IntentFilter("com.so.svg599.musique.ACTION_STATUS_NOTIFICATION_CHANGED"), Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(autoCategoryReceiver, IntentFilter("com.so.svg599.musique.ACTION_AUTO_CATEGORIES_CHANGED"), Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(skipSilenceReceiver, IntentFilter("com.so.svg599.musique.ACTION_SKIP_SILENCE_CHANGED"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, IntentFilter("com.so.svg599.musique.ACTION_STATUS_NOTIFICATION_CHANGED"))
            registerReceiver(autoCategoryReceiver, IntentFilter("com.so.svg599.musique.ACTION_AUTO_CATEGORIES_CHANGED"))
            registerReceiver(skipSilenceReceiver, IntentFilter("com.so.svg599.musique.ACTION_SKIP_SILENCE_CHANGED"))
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 180_000, 2_500, 8_000)
            .build()
        // Le réglage « Ignorer les moments de silence » utilise les segments
        // SponsorBlock et un seekTo réel, uniquement pour les vidéos YouTube.
        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RemotePlayback.dataSourceFactory(this)))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onBandwidthEstimate(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesTransferred: Long, bitrateEstimate: Long) {
                if (bitrateEstimate in 1..300_000L && StreamSources.audioQualityBitrate() > 48) StreamSources.setAudioQuality(false)
                else if (bitrateEstimate > 900_000L && StreamSources.audioQualityBitrate() < 160) StreamSources.setAudioQuality(true)
            }
        })

        // Notification classique distincte : Android masque souvent l'icône de
        // la notification multimédia dans la barre d'état. Celle-ci affiche la note.
        playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && notificationsEnabled()) showStatusIcon(player) else hideStatusIcon()
            }

            // Certains flux HLS sont d'abord présentés comme un format inconnu;
            // on conserve la tentative de résolution spécifique, puis on saute au suivant si elle échoue.
            override fun onPlayerError(error: PlaybackException) {
                val detail = generateSequence<Throwable>(error) { it.cause }.take(5)
                    .joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
                runCatching {
                    getSharedPreferences("player_settings", Context.MODE_PRIVATE).edit().putString("last_error", detail.take(500)).apply()
                }
                val item = player.currentMediaItem ?: run { player.stop(); return }
                val index = player.currentMediaItemIndex
                val failedUri = item.localConfiguration?.uri?.toString().orEmpty()
                val expire = Regex("[?&]expire=(\\d+)").find(failedUri)?.groupValues?.get(1)?.toLongOrNull()
                val urlAge = expire?.let { "expire-in=${it - System.currentTimeMillis()/1000}s" } ?: "expire=unknown"
                val httpCode = Regex("(?:HTTP |code[ =])([0-9]{3})", RegexOption.IGNORE_CASE)
                    .findAll(detail).lastOrNull()?.groupValues?.get(1) ?: "unknown"
                android.util.Log.e("LecteurAudio", "Playback error mime=${item.localConfiguration?.mimeType} itag=${Regex("[?&]itag=(\\d+)").find(failedUri)?.groupValues?.get(1) ?: "unknown"} http=$httpCode $urlAge detail=$detail", error)
                val pageUrl = item.mediaId.takeIf { it.startsWith(StreamSources.ID_PREFIX) }
                    ?.removePrefix(StreamSources.ID_PREFIX)
                val isSoundCloud = pageUrl?.let { android.net.Uri.parse(it).host?.lowercase()?.endsWith("soundcloud.com") } == true
                val retryKey = "$index:${item.mediaId}"
                val retryPositionMs = player.currentPosition.coerceAtLeast(0L)
                val isUnrecognized = generateSequence<Throwable>(error) { it.cause }
                    .any { it is UnrecognizedInputFormatException }
                // SoundCloud peut renvoyer une URL signée périmée/indisponible avec plusieurs codes
                // réseau (401/403/404). Réextraire une fois en privilégiant HLS avant de sauter.
                val isYouTube = pageUrl?.let { android.net.Uri.parse(it).host?.lowercase()?.let { host -> host == "youtu.be" || host.endsWith("youtube.com") } } == true
                val retriable = pageUrl != null && !retriedStreamItems.contains(retryKey) &&
                    (isSoundCloud || isYouTube || (item.localConfiguration?.mimeType != MimeTypes.APPLICATION_M3U8 && isUnrecognized))
                if (retriable) {
                    retriedStreamItems.add(retryKey)
                    val upgradeYoutube = isYouTube && qualityFallbackItems.add(retryKey) && StreamSources.useHigherAudioQuality()
                    libraryExecutor.execute {
                        var resolved: String? = null
                        for (attempt in 0..2) {
                            resolved = runCatching {
                                StreamSources.invalidate(pageUrl!!)
                                StreamSources.resolveAudioUrl(pageUrl, preferSoundCloudHls = isSoundCloud)
                            }.getOrNull()
                            if (resolved != null) break
                            if (attempt < 2) Thread.sleep(700L shl attempt)
                        }
                        Handler(Looper.getMainLooper()).post {
                            if (player.currentMediaItemIndex != index || player.currentMediaItem?.mediaId != item.mediaId) return@post
                            if (resolved != null) {
                                val mime = if (android.net.Uri.parse(resolved).lastPathSegment?.contains(".m3u8", ignoreCase = true) == true)
                                    MimeTypes.APPLICATION_M3U8 else null
                                player.replaceMediaItem(index, item.buildUpon().setUri(resolved).setMimeType(mime).build())
                                player.seekTo(index, retryPositionMs)
                                player.prepare()
                                player.play()
                            } else {
                                advanceAfterPlaybackError(player, index)
                            }
                        }
                    }
                } else {
                    advanceAfterPlaybackError(player, index)
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    failedTrackQueueKey = emptyList()
                    failedTrackIndices.clear()
                    retriedStreamItems.clear()
                    qualityFallbackItems.clear()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { item -> if (item.mediaId.startsWith(StreamSources.ID_PREFIX) && item.mediaId.contains("soundcloud.com", true)) SoundCloudClient.recordPlayed(item) }
                updateSkipSilence(player)
                val startupItem = mediaItem
                if (startupItem?.mediaId?.startsWith(StreamSources.ID_PREFIX) == true) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (player.currentMediaItem?.mediaId == startupItem.mediaId && player.currentPosition < 500L &&
                            (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE)) {
                            val page = startupItem.mediaId.removePrefix(StreamSources.ID_PREFIX)
                            val key = "${player.currentMediaItemIndex}:${startupItem.mediaId}"
                            val youtube = android.net.Uri.parse(page).host?.lowercase()?.let { it == "youtu.be" || it.endsWith("youtube.com") } == true
                            if (youtube && qualityFallbackItems.add(key) && StreamSources.useHigherAudioQuality()) {
                                val index = player.currentMediaItemIndex
                                libraryExecutor.execute {
                                    StreamSources.invalidate(page)
                                    val url = runCatching { StreamSources.resolveAudioUrl(page) }.getOrNull()
                                    Handler(Looper.getMainLooper()).post {
                                        if (url != null && player.currentMediaItem?.mediaId == startupItem.mediaId) {
                                            val type = if (android.net.Uri.parse(url).lastPathSegment?.contains(".m3u8", true) == true) MimeTypes.APPLICATION_M3U8 else null
                                            player.replaceMediaItem(index, startupItem.buildUpon().setUri(url).setMimeType(type).build())
                                            player.seekTo(index, 0L); player.prepare(); player.play()
                                        }
                                    }
                                }
                            }
                        }
                    }, 7_000L)
                }
                // Pré-résolution asynchrone du suivant ; n’affecte pas la lecture active.
                val nextIndex = player.currentMediaItemIndex + 1
                if (nextIndex in 0 until player.mediaItemCount) {
                    val next = player.getMediaItemAt(nextIndex)
                    val page = next.mediaId.takeIf { it.startsWith(StreamSources.ID_PREFIX) }
                        ?.removePrefix(StreamSources.ID_PREFIX)
                    if (page != null) libraryExecutor.execute { runCatching { StreamSources.resolveAudioUrl(page) } }
                }
                if (player.isPlaying && notificationsEnabled()) showStatusIcon(player)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                updateSkipSilence(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) updateSkipSilence(player)
                if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                    hideStatusIcon()
                }
            }
        }
        player.addListener(playerListener!!)
        updateSkipSilence(player)
        getSharedPreferences("player_settings", Context.MODE_PRIVATE).edit().putInt("audio_session_id", player.audioSessionId).apply()
        // Boutons -10 s / +30 s (Android Auto, notification).
        val rewindButton = CommandButton.Builder().setDisplayName("-10 s")
            .setIconResId(R.drawable.ic_rewind_10).setSessionCommand(REWIND_COMMAND).build()
        val forwardButton = CommandButton.Builder().setDisplayName("+30 s")
            .setIconResId(R.drawable.ic_forward_10).setSessionCommand(FORWARD_COMMAND).build()
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setCustomLayout(ImmutableList.of(rewindButton, forwardButton))
            .build()
    }

    /** Saute une piste en erreur sans boucler ni revenir au début de la file. */
    private fun advanceAfterPlaybackError(player: ExoPlayer, failedIndex: Int) {
        val count = player.mediaItemCount
        if (count <= 0 || failedIndex !in 0 until count) {
            player.stop()
            return
        }
        val queueKey = (0 until count).map { player.getMediaItemAt(it).mediaId }
        if (queueKey != failedTrackQueueKey) {
            failedTrackQueueKey = queueKey
            failedTrackIndices.clear()
        }
        failedTrackIndices.add(failedIndex)
        val nextIndex = (failedIndex + 1 until count).firstOrNull { it !in failedTrackIndices }
        if (nextIndex == null || failedTrackIndices.size >= count) {
            player.stop()
            Handler(Looper.getMainLooper()).post { Toast.makeText(this, "Lecture impossible : aucun autre titre disponible", Toast.LENGTH_SHORT).show() }
            return
        }
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, "Titre indisponible, passage au suivant", Toast.LENGTH_SHORT).show() }
        player.seekTo(nextIndex, 0L)
        player.prepare()
        player.play()
    }

    private fun updateSkipSilence(player: ExoPlayer) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateSkipSilence(player) }
            return
        }
        // Désactive explicitement l'accélération / suppression PCM de Media3.
        player.skipSilenceEnabled = false
        silenceHandler.removeCallbacksAndMessages(null)
        val enabled = getSharedPreferences("player_settings", Context.MODE_PRIVATE).getBoolean("skip_silence", false)
        val item = player.currentMediaItem ?: run { activeSilenceVideoId = null; return }
        val pageUrl = item.mediaId.takeIf { it.startsWith(StreamSources.ID_PREFIX) }
            ?.removePrefix(StreamSources.ID_PREFIX)
        val pageUri = pageUrl?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
        val uri = pageUri ?: item.localConfiguration?.uri
        val host = uri?.host?.lowercase().orEmpty()
        val isYouTube = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        val videoId = if (isYouTube) youtubeVideoId(uri) else null
        if (!enabled || videoId == null) { activeSilenceVideoId = null; return }
        if (activeSilenceVideoId != videoId) {
            activeSilenceVideoId = videoId
            skippedMusicOfftopicSegments.clear()
        }
        if (!musicOfftopicSegments.containsKey(videoId)) {
            libraryExecutor.execute {
                val segments = fetchMusicOfftopicSegments(videoId)
                Handler(Looper.getMainLooper()).post {
                    musicOfftopicSegments[videoId] = segments
                    if (activeSilenceVideoId == videoId) scheduleMusicOfftopicSeek(player, videoId)
                }
            }
        } else {
            scheduleMusicOfftopicSeek(player, videoId)
        }
    }

    private fun youtubeVideoId(uri: android.net.Uri?): String? {
        if (uri == null) return null
        val id = if (uri.host.equals("youtu.be", true)) uri.pathSegments.lastOrNull()
            else uri.getQueryParameter("v") ?: uri.pathSegments.lastOrNull()?.takeIf { uri.path?.startsWith("/shorts/") == true || uri.path?.startsWith("/live/") == true }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
    }

    private fun fetchMusicOfftopicSegments(videoId: String): List<Pair<Long, Long>> = runCatching {
        val url = URL("https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=%5B%22music_offtopic%22%5D")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "LecteurAudio/0.54")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) return@runCatching emptyList()
            val results = mutableListOf<Pair<Long, Long>>()
            val entries = JSONArray(body)
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                if (entry.optString("category") != "music_offtopic") continue
                val range = entry.optJSONArray("segment") ?: continue
                if (range.length() < 2) continue
                val start = (range.optDouble(0, -1.0) * 1000).toLong()
                val end = (range.optDouble(1, -1.0) * 1000).toLong()
                if (start >= 0 && end > start) results += start to end
            }
            results.sortedBy { it.first }
        } finally { connection.disconnect() }
    }.getOrDefault(emptyList())

    private fun scheduleMusicOfftopicSeek(player: ExoPlayer, videoId: String) {
        val segments = musicOfftopicSegments[videoId] ?: return
        if (segments.isEmpty()) return
        val check = object : Runnable {
            override fun run() {
                if (activeSilenceVideoId != videoId ||
                    !getSharedPreferences("player_settings", Context.MODE_PRIVATE).getBoolean("skip_silence", false) ||
                    player.currentMediaItem == null) return
                if (!player.isPlaying) {
                    silenceHandler.postDelayed(this, 500L)
                    return
                }
                val position = player.currentPosition
                val segment = segments.firstOrNull { (start, end) ->
                    position >= start && position < end && !skippedMusicOfftopicSegments.contains("$videoId:$start:$end")
                }
                if (segment != null) {
                    val (start, end) = segment
                    skippedMusicOfftopicSegments += "$videoId:$start:$end"
                    player.seekTo(end)
                }
                silenceHandler.postDelayed(this, 350L)
            }
        }
        silenceHandler.post(check)
    }

    private fun createStatusIconChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                STATUS_CHANNEL_ID,
                "Icône du lecteur audio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Affiche une note de musique dans la barre d'état pendant la lecture"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)

        }
    }

    private fun showStatusIcon(player: ExoPlayer) {
        val openApp = PendingIntent.getActivity(
            this,
            701,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = player.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Lecture en cours"
        val notification = Notification.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("LecteurAudio")
            .setContentText(title)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(STATUS_NOTIFICATION_ID, notification)
    }

    private fun notificationsEnabled(): Boolean = getSharedPreferences("player_settings", Context.MODE_PRIVATE).getBoolean("status_notification_enabled", true)

    private fun hideStatusIcon() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(STATUS_NOTIFICATION_ID)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        hideStatusIcon()
        runCatching { statusReceiver?.let { unregisterReceiver(it) } }
        runCatching { autoCategoryReceiver?.let { unregisterReceiver(it) } }
        runCatching { skipSilenceReceiver?.let { unregisterReceiver(it) } }
        statusReceiver = null
        autoCategoryReceiver = null
        skipSilenceReceiver = null
        silenceHandler.removeCallbacksAndMessages(null)
        session?.run { player.release(); release() }
        session = null
        playerListener = null
        libraryExecutor.shutdownNow()
        searchExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        ).build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        // Mémoire des listes affichées dans Android Auto : sert à zapper et à répondre à getItem sans réseau.
        private val knownItems = ConcurrentHashMap<String, MediaItem>()
        private val parentOf = ConcurrentHashMap<String, String>()
        private val playableLists = ConcurrentHashMap<String, List<MediaItem>>()
        private val childrenCache = ConcurrentHashMap<String, Pair<Long, List<MediaItem>>>()

        private fun record(parentId: String, items: List<MediaItem>) {
            if (parentOf.size > 5000) { parentOf.clear(); knownItems.clear(); playableLists.clear() }
            playableLists[parentId] = items.filter { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable != true }
            items.forEach { knownItems[it.mediaId] = it; parentOf[it.mediaId] = parentId }
        }

        /** Listes YouTube / SoundCloud gardées 5 minutes (Android Auto les redemande souvent). */
        private fun cachedAccountChildren(parentId: String): List<MediaItem> {
            val secret: String? = if (parentId.startsWith("yt:")) YtAuth.cookie else ScAuth.token
            val key = parentId + "#" + secret.hashCode()
            val now = System.currentTimeMillis()
            childrenCache[key]?.let { (time, list) -> if (now - time < 5 * 60_000L) return list }
            val list = accountChildren(parentId)
            // Une réponse vide est toujours rechargée: elle peut être transitoire ou obsolète.
            if (list.isNotEmpty() && list.none { it.mediaId.endsWith(":error") || it.mediaId.endsWith(":login") }) childrenCache[key] = now to list
            return list
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = knownItems[mediaId] ?: when {
                mediaId == ROOT -> folder(ROOT, "Lecteur")
                mediaId.startsWith("stream:") -> MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(mediaId.removePrefix("stream:"))
                    .setMediaMetadata(
                        MediaMetadata.Builder().setIsPlayable(true).setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
                    ).build()
                else -> null
            }
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onSubscribe(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(LibraryResult.ofVoid())

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    folder(ROOT, "Lecteur"),
                    LibraryParams.Builder()
                        .setExtras(Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) })
                        .setRecent(params?.isRecent ?: false)
                        .setOffline(params?.isOffline ?: false)
                        .setSuggested(params?.isSuggested ?: false)
                        .build()
                )
            )

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(REWIND_COMMAND).add(FORWARD_COMMAND).build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_BACK).add(Player.COMMAND_SEEK_FORWARD).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands).setAvailablePlayerCommands(playerCommands).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            val delta = when (customCommand.customAction) {
                REWIND_COMMAND.customAction -> -10_000L
                FORWARD_COMMAND.customAction -> 30_000L
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            val p = session.player
            val duration = p.duration
            val target = p.currentPosition + delta
            p.seekTo(
                if (duration != C.TIME_UNSET && duration > 0) target.coerceIn(0L, (duration - 500).coerceAtLeast(0L))
                else target.coerceAtLeast(0L)
            )
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // ---------- Recherche (champ de recherche d'Android Auto) ----------
        private val searchResults = ConcurrentHashMap<String, List<MediaItem>>()

        private fun searchAll(query: String): List<MediaItem> {
            val yt = runCatching { StreamSources.search(StreamSources.YOUTUBE, query) }.getOrDefault(emptyList()).take(25)
            val soundCloud = runCatching { SoundCloudClient.search(query) }.getOrDefault(emptyList()).take(25)
            return yt + soundCloud
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            searchExecutor.execute {
                val results = searchAll(query)
                searchResults[query] = results
                record("search:$query", results)
                session.notifySearchResultChanged(browser, query, results.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.submit(Callable {
                val all = searchResults[query] ?: searchAll(query).also {
                    searchResults[query] = it
                    record("search:$query", it)
                }
                val from = (page.toLong() * pageSize).coerceIn(0L, all.size.toLong()).toInt()
                val to = (from.toLong() + pageSize).coerceIn(0L, all.size.toLong()).toInt()
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            }, searchExecutor)

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.submit(Callable {
                val items: List<MediaItem> = when {
                    parentId == ROOT -> buildList<MediaItem> {
                        val enabled = getSharedPreferences("player_settings", Context.MODE_PRIVATE)
                            .getStringSet("auto_categories", setOf("local", "pcloud", "youtube", "soundcloud", "smb", "ftp")) ?: emptySet()
                        val hidden = getSharedPreferences("player_settings", Context.MODE_PRIVATE)
                            .getStringSet("auto_hidden_sources", emptySet()) ?: emptySet()
                        if ("Local" !in hidden && "local" in enabled) add(folder(LOCAL, "Musique locale"))
                        if ("pCloud" !in hidden && "pcloud" in enabled) add(folder(PCLOUD_ROOT, "pCloud"))
                        if ("SMB" !in hidden && "smb" in enabled) add(folder("remote:smb", "SMB"))
                        if ("FTP" !in hidden && "ftp" in enabled) add(folder("remote:ftp", "FTP"))
                        if ("YouTube" !in hidden && "youtube" in enabled) add(folder("yt:root", "YouTube Music"))
                        if ("SoundCloud" !in hidden && "soundcloud" in enabled) add(folder("sc:root", "SoundCloud"))
                    }
                    parentId=="remote:smb" || parentId=="remote:ftp" -> RemoteProfiles.all(this@PlaybackService).filter{it.type==if(parentId.endsWith("smb"))"smb" else "ftp"}.map{p->folder("remote:profile:${p.id}",p.name)}
                    parentId.startsWith("remote:profile:") -> { val p=RemoteProfiles.get(this@PlaybackService,parentId.removePrefix("remote:profile:")); if(p==null)emptyList() else listRemoteItems(p,"") }
                    parentId.startsWith("remote:path:") -> { val parts=parentId.removePrefix("remote:path:").split("|",limit=2); val p=RemoteProfiles.get(this@PlaybackService,parts[0]); if(p==null)emptyList() else listRemoteItems(p,parts.getOrElse(1){""}) }
                    parentId == LOCAL -> if ("Local" !in getSharedPreferences("player_settings", Context.MODE_PRIVATE).getStringSet("auto_hidden_sources", emptySet()).orEmpty() && "local" in getSharedPreferences("player_settings", Context.MODE_PRIVATE).getStringSet("auto_categories", setOf("local", "pcloud", "youtube", "soundcloud", "smb", "ftp")).orEmpty()) LocalLibrary.tracks(this@PlaybackService) else emptyList()
                    parentId == PCLOUD_ROOT -> {
                        if ("pCloud" in getSharedPreferences("player_settings", Context.MODE_PRIVATE).getStringSet("auto_hidden_sources", emptySet()).orEmpty()) emptyList()
                        else if (!WebDavAuth.isConfigured()) {
                            listOf(infoItem("pcloud:login-required", "Connecte-toi à pCloud sur le téléphone"))
                        } else {
                            listWebDavItems(WebDavAuth.baseUrl)
                        }
                    }
                    parentId.startsWith(PCLOUD_PREFIX) -> {
                        val url = parentId.removePrefix(PCLOUD_PREFIX)
                        if (!WebDavAuth.isConfigured()) {
                            listOf(infoItem("pcloud:login-required", "Connecte-toi à pCloud sur le téléphone"))
                        } else {
                            listWebDavItems(url)
                        }
                    }
                    parentId.startsWith("yt:") && "YouTube" !in getSharedPreferences("player_settings", Context.MODE_PRIVATE).getStringSet("auto_hidden_sources", emptySet()).orEmpty() && "youtube" in getSharedPreferences("player_settings", Context.MODE_PRIVATE).getStringSet("auto_categories", setOf("local", "pcloud", "youtube", "soundcloud", "smb", "ftp")).orEmpty() -> cachedAccountChildren(parentId)
                    parentId.startsWith("sc:") && "SoundCloud" !in getSharedPreferences("player_settings", Context.MODE_PRIVATE).getStringSet("auto_hidden_sources", emptySet()).orEmpty() && "soundcloud" in getSharedPreferences("player_settings", Context.MODE_PRIVATE).getStringSet("auto_categories", setOf("local", "pcloud", "youtube", "soundcloud", "smb", "ftp")).orEmpty() -> cachedAccountChildren(parentId)
                    else -> emptyList()
                }
                record(parentId, items)
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }, libraryExecutor)

        private fun listRemoteItems(p:RemoteProfile,path:String):List<MediaItem> = RemoteFiles.list(p,path).filter{it.directory||isAudioFile(it.name)}.map{e->
            val md=MediaMetadata.Builder().setTitle(if(e.directory)e.name else e.name.substringBeforeLast('.',e.name)).setArtist(p.name).setIsBrowsable(e.directory).setIsPlayable(!e.directory).setMediaType(if(e.directory)MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_MUSIC).build()
            MediaItem.Builder().setMediaId(if(e.directory)"remote:path:${p.id}|${e.path}" else "${p.id}:${e.path}").apply{if(!e.directory)setUri(RemoteFiles.uri(p,e.path))}.setMediaMetadata(md).build()
        }

        private fun listWebDavItems(url: String): List<MediaItem> = WebDavClient.list(url)
            .filter { it.isDirectory || isAudioFile(it.name) }
            .map { entry ->
                if (entry.isDirectory) {
                    MediaItem.Builder()
                        .setMediaId(PCLOUD_PREFIX + entry.url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(entry.name)
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                .build()
                        ).build()
                } else {
                    MediaItem.Builder()
                        .setMediaId("pcloud-audio:" + entry.url)
                        .setUri(entry.url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(entry.name.substringBeforeLast('.', entry.name))
                                .setArtist("pCloud")
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                        ).build()
                }
            }

        private fun infoItem(id: String, title: String) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title)
                    .setIsBrowsable(false).setIsPlayable(false).build()
            ).build()

        /** Onglet YouTube : Accueil en premier, puis les playlists (sans « Musique que j'aime »). */
        private fun youtubeRoot(): List<MediaItem> = buildList {
            add(folder("yt:home", "Accueil"))
            try {
                YtMusicClient.playlists().filterNot { isLikedPlaylist(it) }
                    .forEach { add(folder("yt:pl:" + it.id, it.title)) }
            } catch (e: YtAuthException) {
                throw e
            } catch (e: Exception) {
                add(infoItem("yt:root:error", "Playlists indisponibles : ${e.message}"))
            }
        }

        private fun isLikedPlaylist(p: AccountPlaylist): Boolean {
            val title = p.title.lowercase().replace('’', '\'')
            return p.id == "VLLM" || title.contains("j'aime") || title.contains("liked music") || title.contains("titres aimés")
        }

        private fun youtubeHome(): List<MediaItem> = YtMusicClient.home().tracks.take(60)

        /** Onglet SoundCloud : les uploads sont affichés directement, les autres catégories sont en tête de liste. */
        private fun soundCloudRoot(): List<MediaItem> =
            listOf(folder("sc:playlists", "Playlists"), folder("sc:history", "Historique"), folder("sc:liked", "Aimés")) +
                SoundCloudClient.uploads()

        /** Arborescence Android Auto des comptes YouTube Music et SoundCloud (connexion faite sur le téléphone). */
        private fun accountChildren(parentId: String): List<MediaItem> {
            val isYt = parentId.startsWith("yt:")
            val loggedIn = if (isYt) YtAuth.isLoggedIn() else ScAuth.isLoggedIn()
            if (!loggedIn) {
                return listOf(infoItem("$parentId:login", "Connecte-toi à ${if (isYt) "YouTube Music" else "SoundCloud"} sur le téléphone"))
            }
            return try {
                when {
                    parentId == "yt:root" -> youtubeRoot()
                    parentId == "yt:home" -> youtubeHome()
                    parentId == "yt:playlists" -> YtMusicClient.playlists().map { folder("yt:pl:" + it.id, it.title) }
                    parentId == "yt:history" -> YtMusicClient.history()
                    parentId == "yt:liked" -> YtMusicClient.likedSongs()
                    parentId.startsWith("yt:pl:") -> YtMusicClient.playlistTracks(parentId.removePrefix("yt:pl:"))
                    parentId == "sc:root" -> soundCloudRoot()
                    parentId == "sc:uploads" -> SoundCloudClient.uploads()
                    parentId == "sc:playlists" -> SoundCloudClient.playlists().map { folder("sc:pl:" + it.id, it.title) }
                    parentId == "sc:history" -> SoundCloudClient.history()
                    parentId == "sc:liked" -> SoundCloudClient.likes()
                    parentId.startsWith("sc:pl:") -> SoundCloudClient.playlistTracks(parentId.removePrefix("sc:pl:"))
                    else -> emptyList()
                }
            } catch (e: Exception) {
                listOf(infoItem("$parentId:error", e.message ?: "Erreur de chargement"))
            }
        }

        private fun isAudioFile(name: String): Boolean = name.substringAfterLast('.', "")
            .lowercase() in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff", "alac")

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(resolveItems(mediaItems))

        /** Un titre choisi dans une liste d'Android Auto : toute la liste passe en file d'attente, pour pouvoir zapper. */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Commande vocale (« joue … sur Lecteur ») : pas d'identifiant, seulement un texte à chercher.
            val voice = mediaItems.singleOrNull()?.takeIf { it.mediaId.isEmpty() && it.localConfiguration == null }
            if (voice != null) {
                val query = voice.requestMetadata.searchQuery
                return Futures.submit(Callable {
                    val found: List<MediaItem> = if (query.isNullOrBlank()) emptyList() else searchAll(query)
                    if (found.isEmpty()) {
                        MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                    } else {
                        record("search:$query", found)
                        MediaSession.MediaItemsWithStartPosition(resolveItems(found), 0, startPositionMs)
                    }
                }, searchExecutor)
            }
            val resolved = resolveItems(mediaItems)
            if (resolved.size == 1 && controller.packageName != packageName) {
                val id = resolved[0].mediaId
                val siblings = parentOf[id]?.let { playableLists[it] }
                val index = siblings?.indexOfFirst { it.mediaId == id } ?: -1
                if (siblings != null && index >= 0) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(resolveItems(siblings), index, startPositionMs)
                    )
                }
            }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs))
        }

        private fun resolveItems(mediaItems: List<MediaItem>): MutableList<MediaItem> =
            mediaItems.map { item ->
                val requestedUri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
                val uri = when {
                    requestedUri != null -> requestedUri
                    item.mediaId.contains(":") && item.mediaId.substringBefore(":").let{RemoteProfiles.get(this@PlaybackService,it)!=null} -> { val parts=item.mediaId.split(":",limit=2); val p=RemoteProfiles.get(this@PlaybackService,parts[0]); if(p!=null)RemoteFiles.uri(p,parts[1]) else null }
                    item.mediaId.startsWith("pcloud-audio:") ->
                        android.net.Uri.parse(item.mediaId.removePrefix("pcloud-audio:"))
                    item.mediaId.startsWith("webdav:") ->
                        android.net.Uri.parse(item.mediaId.removePrefix("webdav:"))
                    item.mediaId.startsWith("stream:") ->
                        android.net.Uri.parse(item.mediaId.removePrefix("stream:"))
                    item.mediaId.startsWith("local:") -> {
                        val audioId = item.mediaId.removePrefix("local:").toLongOrNull()
                        audioId?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
                    }
                    else -> null
                }
                val cached = knownItems[item.mediaId]
                val currentExtras = item.mediaMetadata.extras
                val cachedExtras = cached?.mediaMetadata?.extras
                val hasCreator = !currentExtras?.getString("creator_profile_url").isNullOrBlank() ||
                    !currentExtras?.getString("creator_permalink").isNullOrBlank() ||
                    !currentExtras?.getString("creator_browse_id").isNullOrBlank() ||
                    !currentExtras?.getString("creator_id").isNullOrBlank()
                val hasCachedCreator = !cachedExtras?.getString("creator_profile_url").isNullOrBlank() ||
                    !cachedExtras?.getString("creator_permalink").isNullOrBlank() ||
                    !cachedExtras?.getString("creator_browse_id").isNullOrBlank() ||
                    !cachedExtras?.getString("creator_id").isNullOrBlank()
                val resolved = if (uri != null) item.buildUpon().setUri(uri) else item.buildUpon()
                if (!hasCreator && hasCachedCreator && cached != null) {
                    resolved.setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(cachedExtras).build())
                }
                resolved.build()
            }.toMutableList()
    }


    private companion object {
        const val ROOT = "root"
        const val LOCAL = "local"
        val REWIND_COMMAND = SessionCommand("lecteur.rewind10", Bundle.EMPTY)
        val FORWARD_COMMAND = SessionCommand("lecteur.forward10", Bundle.EMPTY)
        const val PCLOUD_ROOT = "pcloud:root"
        const val PCLOUD_PREFIX = "pcloud:url:"
        const val STATUS_CHANNEL_ID = "lecteuraudio_status_icon"
        const val STATUS_NOTIFICATION_ID = 701
    }
}
