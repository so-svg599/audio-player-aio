package com.so.svg599.musique

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(PlayerUiState())
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private val webDavStack = mutableListOf<String>()
    private val remoteStack = mutableListOf<String>()
    private var lastBackPress = 0L
    private var artistProfileReturnState: PlayerUiState? = null
    private val settings by lazy { getSharedPreferences("player_settings", MODE_PRIVATE) }
    private var ytHomeTracks: List<MediaItem> = emptyList()
    private var scHomeTracks: List<MediaItem> = emptyList()
    private var scHomeNeedsRefresh = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadTracks() }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val keep = settings.getBoolean("remember_playback_parameters", false)
            val speed = if (keep) settings.getFloat("playback_speed", 1f) else 1f
            val pitch = if (keep) settings.getFloat("playback_pitch", 1f) else 1f
            controller?.setPlaybackParameters(PlaybackParameters(speed, pitch))
        }
        // Les erreurs sont gérées dans PlaybackService (saut automatique, sans toast).
        override fun onPlayerError(error: PlaybackException) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        YtAuth.load(this)
        ScAuth.load(this)
        // Récupération immédiate du client_id puis renouvellement régulier même si l'utilisateur
        // laisse l'onglet SoundCloud ouvert longtemps.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { SoundCloudClient.refreshClientId() } }
            while (true) {
                delay(20 * 60 * 1000L)
                withContext(Dispatchers.IO) { runCatching { SoundCloudClient.refreshClientId() } }
            }
        }
        StreamSources.setAudioQuality(settings.getBoolean("high_audio_quality", true))
        settings.edit().remove("hide_subtabs").remove("local_yt_history").apply() // Nettoyage des préférences obsolètes.
        val persistedAspect = settings.getString("video_aspect", "fit") ?: "fit"
        if (persistedAspect !in setOf("fit", "fill")) settings.edit().putString("video_aspect", "fit").apply()
        val defaultHiddenSources = setOf("Tout")
        if (!settings.contains("hidden_sources")) {
            settings.edit().putStringSet("hidden_sources", defaultHiddenSources).apply()
        }
        val savedHiddenSources = settings.getStringSet("hidden_sources", defaultHiddenSources)?.toSet() ?: defaultHiddenSources
        val hiddenAtStart = if (ALL_SOURCES.all { it in savedHiddenSources }) defaultHiddenSources else savedHiddenSources
        val initialVisibleSources = ALL_SOURCES.filter { it !in hiddenAtStart }
        if (hiddenAtStart != savedHiddenSources) {
            settings.edit().putStringSet("hidden_sources", hiddenAtStart).apply()
        }
        state = state.copy(
            remoteProfiles = RemoteProfiles.all(this),
            ytLoggedIn = YtAuth.isLoggedIn(),
            scLoggedIn = ScAuth.isLoggedIn(),
            accentColorKey = settings.getString("accent_color", "Vert") ?: "Vert",
            statusNotificationEnabled = settings.getBoolean("status_notification_enabled", true),
            disabledSubTabs = settings.getStringSet("disabled_subtabs", emptySet())?.toSet() ?: emptySet(),
            showArtwork = settings.getBoolean("show_artwork", false),
            youtubeVideoEnabled = settings.getBoolean("youtube_video_enabled", false),
            videoAspect = settings.getString("video_aspect", "fit")?.takeIf { it in setOf("fit", "fill") } ?: "fit",
            autoCategories = settings.getStringSet("auto_categories", setOf("local", "pcloud", "youtube", "soundcloud", "smb", "ftp"))?.toSet() ?: setOf("local", "pcloud", "youtube", "soundcloud", "smb", "ftp"),
            hiddenAutoSources = settings.getStringSet("auto_hidden_sources", emptySet())?.toSet() ?: emptySet(),
            gesturesEnabled = settings.getBoolean("gestures_enabled", false),
            queueSwipeEnabled = settings.getBoolean("gestures_enabled", false),
            highAudioQuality = settings.getBoolean("high_audio_quality", true),
            skipSilence = settings.getBoolean("skip_silence", false),
            rememberPlaybackParameters = settings.getBoolean("remember_playback_parameters", false),
            hiddenSources = hiddenAtStart,
            selectedSource = initialVisibleSources.firstOrNull() ?: "Local",
            playbackSpeed = if (settings.getBoolean("remember_playback_parameters", false)) (kotlin.math.round(settings.getFloat("playback_speed", 1f) * 100f) / 100f).coerceIn(0.5f, 2f) else 1f,
            playbackPitch = if (settings.getBoolean("remember_playback_parameters", false)) (kotlin.math.round(settings.getFloat("playback_pitch", 1f) * 100f) / 100f).coerceIn(0.5f, 2f) else 1f,
            localPlaylists = LocalPlaylists.names(this),
        )
        setContent {
            PlayerApp(
                state = state,
                onPlay = { i -> controller?.run { setMediaItems(state.tracks, i, 0L); prepare(); play() } },
                onSelectSource = { source -> onSourceSelected(source) },
                onToggle = { controller?.run { if (isPlaying) pause() else play() } },
                onNext = { controller?.seekToNext() },
                onPrev = { controller?.seekToPrevious() },
                onSeek = { controller?.seekTo(it) },
                onSeekBy = { ms ->
                    controller?.run {
                        val target = currentPosition + ms
                        seekTo(if (duration > 0) target.coerceIn(0L, (duration - 500).coerceAtLeast(0L)) else target.coerceAtLeast(0L))
                    }
                },
                onWebDavConnect = { server, email, password, remember -> connectWebDav(server, email, password, remember) },
                onWebDavBrowse = { url -> browseWebDav(url) },
                onWebDavBack = { goBackWebDav() },
                onWebDavDisconnect = { disconnectWebDav() },
                onPlayRemote = { entry -> playRemote(entry) },
                onSaveRemoteProfile = { profile -> RemoteProfiles.save(this, profile); state = state.copy(remoteProfiles=RemoteProfiles.all(this)) },
                onSelectRemoteProfile = { id -> selectRemoteProfile(id) },
                onBrowseRemote = { path -> browseRemote(path) },
                onBackRemote = { backRemote() },
                onPlayRemoteEntry = { entry -> playRemoteEntry(entry) },
                onStreamSearch = { source, query -> searchStreams(source, query) },
                onSearchArtist = { artist ->
                    state = state.copy(selectedSource = StreamSources.YOUTUBE, isNowPlayingExpanded = false, artistProfileVisible = false, artistProfileTitle = null)
                    searchStreams(StreamSources.YOUTUBE, cleanArtist(artist))
                },
                onPlayStream = { index -> playStream(index) },
                yt = ytActions,
                sc = scActions,
                onOpenNow = { state = state.copy(isNowPlayingExpanded = true) },
                onCloseNow = { state = state.copy(isNowPlayingExpanded = false) },
                onThemeChange = { key -> settings.edit().putString("accent_color", key).apply(); state = state.copy(accentColorKey = key) },
                onNotificationToggle = { enabled ->
                    settings.edit().putBoolean("status_notification_enabled", enabled).apply()
                    state = state.copy(statusNotificationEnabled = enabled)
                    sendBroadcast(android.content.Intent("com.so.svg599.musique.ACTION_STATUS_NOTIFICATION_CHANGED").setPackage(packageName).putExtra("enabled", enabled))
                },
                onArtworkToggle = { enabled -> settings.edit().putBoolean("show_artwork", enabled).apply(); state = state.copy(showArtwork = enabled) },
                onYoutubeVideoToggle = { enabled -> settings.edit().putBoolean("youtube_video_enabled", enabled).apply(); state = state.copy(youtubeVideoEnabled = enabled) },
                onVideoAspectChange = { aspect -> settings.edit().putString("video_aspect", aspect).apply(); state = state.copy(videoAspect = aspect) },
                onDownloadTrack = { item, destination -> downloadStreamTrack(item, destination) },
                onAutoCategoryToggle = { category, enabled ->
                    val categories = state.autoCategories.toMutableSet().apply { if (enabled) add(category) else remove(category) }
                    settings.edit().putStringSet("auto_categories", categories).apply()
                    state = state.copy(autoCategories = categories)
                    sendBroadcast(android.content.Intent("com.so.svg599.musique.ACTION_AUTO_CATEGORIES_CHANGED").setPackage(packageName))
                },
                onAutoSourceVisibilityToggle = { source, visible ->
                    val hidden = state.hiddenAutoSources.toMutableSet().apply { if (visible) remove(source) else add(source) }
                    if (ALL_SOURCES.any { it !in hidden }) {
                        settings.edit().putStringSet("auto_hidden_sources", hidden).apply()
                        state = state.copy(hiddenAutoSources = hidden)
                        sendBroadcast(android.content.Intent("com.so.svg599.musique.ACTION_AUTO_CATEGORIES_CHANGED").setPackage(packageName))
                    }
                },
                onSubTabVisibilityToggle = { tab, visible ->
                    val disabled = state.disabledSubTabs.toMutableSet().apply { if (visible) remove(tab) else add(tab) }
                    val ytTabs = listOf("Accueil", "Playlists", "Uploads", "Historique", "Aimés").filterNot { it in disabled }
                    val scTabs = listOf("Accueil", "Uploads", "Playlists", "Historique", "Aimés").filterNot { it in disabled }
                    if (ytTabs.isNotEmpty() && scTabs.isNotEmpty()) {
                        settings.edit().putStringSet("disabled_subtabs", disabled).apply()
                        val switchingYt = state.selectedSource == StreamSources.YOUTUBE && state.ytView in disabled
                        val switchingSc = state.selectedSource == StreamSources.SOUNDCLOUD && state.scView in disabled
                        state = state.copy(disabledSubTabs = disabled)
                        if (switchingYt) ytShow(ytTabs.first())
                        if (switchingSc) scShow(scTabs.first())
                    }
                },
                onGesturesToggle = { on ->
                    settings.edit().putBoolean("gestures_enabled", on).apply()
                    state = state.copy(gesturesEnabled = on, queueSwipeEnabled = on)
                },
                onQueueSwipeToggle = { on ->
                    settings.edit().putBoolean("gestures_enabled", on).apply()
                    state = state.copy(queueSwipeEnabled = on, gesturesEnabled = on)
                },
                onAudioQualityToggle = { high ->
                    settings.edit().putBoolean("high_audio_quality", high).apply()
                    StreamSources.setAudioQuality(high)
                    state = state.copy(highAudioQuality = high)
                },
                onSkipSilenceToggle = { enabled ->
                    settings.edit().putBoolean("skip_silence", enabled).apply()
                    state = state.copy(skipSilence = enabled)
                    sendBroadcast(android.content.Intent("com.so.svg599.musique.ACTION_SKIP_SILENCE_CHANGED").setPackage(packageName).putExtra("enabled", enabled))
                },
                onSourceVisibilityToggle = { source, visible ->
                    val hidden = state.hiddenSources.toMutableSet()
                    if (visible) hidden.remove(source) else hidden.add(source)
                    val visibleSources = ALL_SOURCES.filter { it !in hidden }
                    if (visibleSources.isNotEmpty()) {  // au moins une catégorie reste visible
                        settings.edit().putStringSet("hidden_sources", hidden).apply()
                        state = state.copy(hiddenSources = hidden)
                        if (state.selectedSource in hidden) onSourceSelected(visibleSources.first())
                    }
                },
                onOpenArtist = { item -> openArtistProfile(item) },
                onCloseArtistProfile = { closeArtistProfile() },
                onPlayProfileTrack = { index -> playArtistProfileTrack(index) },
                onOpenQueue = { refreshQueue() },
                onPlayQueueTrack = { index -> controller?.run { if (index in 0 until mediaItemCount) { seekTo(index, 0L); prepare(); play() } } },
                onCreateYtPlaylist = { title -> createYtPlaylist(title) },
                onAddYtPlaylist = { playlistId -> addCurrentTrackToYtPlaylist(playlistId) },
                onLoadYtPlaylists = { loadPlaylistPicker(StreamSources.YOUTUBE) },
                onDeleteRemoteProfile = { id -> RemoteProfiles.delete(this, id); if (state.remoteProfileId == id) remoteStack.clear(); state = state.copy(remoteProfiles = RemoteProfiles.all(this), remoteProfileId = if (state.remoteProfileId == id) null else state.remoteProfileId, remoteEntries = if (state.remoteProfileId == id) emptyList() else state.remoteEntries) },
                onOpenLocalPlaylist = { name -> openLocalPlaylist(name) },
                onEqualizer = { launchEqualizer() },
                onPlaybackParametersChange = { speed, pitch, remember ->
                    val safeSpeed = (kotlin.math.round(speed * 100f) / 100f).coerceIn(0.5f, 2f)
                    val safePitch = (kotlin.math.round(pitch * 100f) / 100f).coerceIn(0.5f, 2f)
                    settings.edit().putBoolean("remember_playback_parameters", remember)
                        .putFloat("playback_speed", safeSpeed).putFloat("playback_pitch", safePitch).apply()
                    val appliedSpeed = if (remember) safeSpeed else 1f
                    val appliedPitch = if (remember) safePitch else 1f
                    state = state.copy(playbackSpeed = appliedSpeed, playbackPitch = appliedPitch, rememberPlaybackParameters = remember)
                    controller?.setPlaybackParameters(PlaybackParameters(appliedSpeed, appliedPitch))
                },
            )
        }
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.READ_MEDIA_AUDIO); add(Manifest.permission.POST_NOTIFICATIONS) }
            else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        permissions.launch(needed.toTypedArray())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (state.ytLoginVisible || state.scLoginVisible) {
                    state = state.copy(ytLoginVisible = false, scLoginVisible = false)
                    return
                }
                if (state.artistProfileVisible) {
                    closeArtistProfile()
                    return
                }
                // Pendant la lecture plein écran, Retour revient à la bibliothèque sans arrêter la musique.
                if (state.isNowPlayingExpanded) {
                    state = state.copy(isNowPlayingExpanded = false)
                    return
                }
                if (remoteStack.size > 1 && state.remoteProfileId != null) { backRemote(); return }
                if (webDavStack.size > 1 && state.webDavConnected) {
                    goBackWebDav()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2000L) {
                    // Quitter depuis l'accueil doit réellement arrêter la lecture, pas seulement fermer l'interface.
                    controller?.run {
                        stop()
                        clearMediaItems()
                    }
                    state = state.copy(current = null, isPlaying = false, isNowPlayingExpanded = false, position = 0L, duration = 0L)
                    finish()
                } else {
                    lastBackPress = now
                    Toast.makeText(this@MainActivity, "Appuie encore une fois sur Retour pour quitter", Toast.LENGTH_SHORT).show()
                }
            }
        })
        lifecycleScope.launch {
            // Reconnect automatically only if the user explicitly enabled the checkbox.
            runCatching { SecureCredentials.load(this@MainActivity) }.getOrNull()?.let { saved ->
                connectWebDav(saved.server, saved.email, saved.password, remember = true)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        future = MediaController.Builder(this, token).buildAsync().also { f ->
            f.addListener({
                controller = f.get().also {
                    it.addListener(listener)
                    it.setPlaybackParameters(PlaybackParameters(state.playbackSpeed, state.playbackPitch))
                }
                refresh()
            }, MoreExecutors.directExecutor())
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { controller?.let { state = state.copy(position = it.currentPosition) }; delay(500) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ScAuth.reload() }
            runCatching { SoundCloudClient.refreshClientId() }
        }
        // WebView conserve la session du fournisseur; resynchronise les valeurs de session
        // récentes vers le stockage chiffré sans déconnecter en cas de cookie absent.
        runCatching {
            val yt = CookieManager.getInstance().getCookie("https://music.youtube.com")
            if (!yt.isNullOrBlank() && Regex("(?:^|;\\s*)(?:__Secure-3PAPISID|SAPISID)=").containsMatchIn(yt)) YtAuth.save(this, yt)
            val sc = CookieManager.getInstance().getCookie("https://soundcloud.com")
            val token = sc?.let { Regex("(?:^|;\\s*)oauth_token=([^;]+)").find(it)?.groupValues?.get(1) }
            if (!token.isNullOrBlank()) {
                // Un '+' non échappé dans un cookie est un caractère du jeton, pas un espace.
                val decoded = java.net.URLDecoder.decode(token.replace("+", "%2B"), "UTF-8")
                if (decoded != ScAuth.token) { ScAuth.save(this, decoded); SoundCloudClient.reset() }
            }
        }
    }

    override fun onStop() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    private fun loadTracks() {
        state = state.copy(tracks = runCatching { LocalLibrary.tracks(this) }.getOrDefault(emptyList()))
    }

    private fun selectRemoteProfile(id:String) {
        val profile=RemoteProfiles.get(this,id) ?: return
        state=state.copy(remoteProfileId=id,remotePath="",remoteLoading=true,remoteError=null,remoteEntries=emptyList())
        remoteStack.clear(); remoteStack.add("")
        lifecycleScope.launch { try { val entries=withContext(Dispatchers.IO){RemoteFiles.list(profile,"")}; state=state.copy(remoteEntries=entries,remoteLoading=false) } catch(e:Exception){state=state.copy(remoteLoading=false,remoteError=e.message?:"Connexion distante impossible")} }
    }
    private fun browseRemote(path:String) {
        val p=state.remoteProfileId?.let{RemoteProfiles.get(this,it)}?:return
        state=state.copy(remoteLoading=true,remoteError=null)
        lifecycleScope.launch { try {
            val entries = withContext(Dispatchers.IO) { RemoteFiles.list(p, path) }
            val existing = remoteStack.indexOf(path)
            if (existing >= 0) while (remoteStack.size > existing + 1) remoteStack.removeAt(remoteStack.lastIndex)
            else if (remoteStack.lastOrNull() != path) remoteStack.add(path)
            state = state.copy(remoteEntries = entries, remotePath = path, remoteLoading = false)
        } catch (e: Exception) {
            state = state.copy(remoteLoading = false, remoteError = e.message ?: "Impossible d'ouvrir ce dossier")
        } }
    }
    private fun backRemote() {
        if (remoteStack.size > 1) {
            remoteStack.removeAt(remoteStack.lastIndex)
            browseRemote(remoteStack.last())
        } else {
            remoteStack.clear()
            state = state.copy(remoteProfileId = null, remoteEntries = emptyList(), remotePath = "", remoteError = null)
        }
    }
    private fun playRemoteEntry(entry:RemoteEntry) {
        val p=state.remoteProfileId?.let{RemoteProfiles.get(this,it)}?:return
        val queue=state.remoteEntries.filter{!it.directory&&isAudioFileName(it.name)}.map{e-> MediaItem.Builder().setMediaId("${p.id}:${e.path}").setUri(RemoteFiles.uri(p,e.path)).setMediaMetadata(MediaMetadata.Builder().setTitle(e.name.substringBeforeLast('.',e.name)).setArtist("${p.type.uppercase()} · ${p.name}").setIsPlayable(true).build()).build()}
        val index=state.remoteEntries.filter{!it.directory&&isAudioFileName(it.name)}.indexOfFirst{it.path==entry.path}.coerceAtLeast(0)
        controller?.run{setMediaItems(queue,index,0L);prepare();play()}
    }

    private fun connectWebDav(server: String, email: String, password: String, remember: Boolean) {
        state = state.copy(webDavLoading = true, webDavError = null, webDavRoot = server.trimEnd('/'), webDavPath = server.trimEnd('/'))
        lifecycleScope.launch {
            try {
                WebDavAuth.configure(server, email, password)
                val entries = withContext(Dispatchers.IO) { WebDavClient.list(WebDavAuth.baseUrl) }
                if (remember) SecureCredentials.save(this@MainActivity, server, email, password)
                else SecureCredentials.clear(this@MainActivity)
                webDavStack.clear(); webDavStack.add(WebDavAuth.baseUrl)
                state = state.copy(webDavConnected = true, webDavEntries = entries, webDavPath = WebDavAuth.baseUrl, webDavRoot = WebDavAuth.baseUrl, webDavUsername = email, webDavLoading = false, webDavError = null)
            } catch (e: Exception) {
                WebDavAuth.clear()
                state = state.copy(webDavConnected = false, webDavEntries = emptyList(), webDavLoading = false, webDavError = e.message ?: "Connexion WebDAV impossible")
            }
        }
    }

    private fun browseWebDav(url: String) {
        state = state.copy(webDavLoading = true, webDavError = null)
        lifecycleScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { WebDavClient.list(url) }
                if (webDavStack.lastOrNull() != url) webDavStack.add(url)
                state = state.copy(webDavEntries = entries, webDavPath = url, webDavLoading = false, webDavError = null)
            } catch (e: Exception) {
                state = state.copy(webDavLoading = false, webDavError = e.message ?: "Impossible d'ouvrir ce dossier")
            }
        }
    }

    private fun goBackWebDav() {
        if (webDavStack.size > 1) {
            webDavStack.removeAt(webDavStack.lastIndex)
            browseWebDav(webDavStack.last())
        }
    }

    private fun disconnectWebDav() {
        WebDavAuth.clear(); webDavStack.clear()
        runCatching { SecureCredentials.clear(this) }
        state = state.copy(webDavConnected = false, webDavEntries = emptyList(), webDavPath = "", webDavUsername = "", webDavError = null, webDavLoading = false)
    }

    private fun playRemote(entry: WebDavEntry) {
        // Construire une file d'attente avec tous les fichiers audio du dossier affiché.
        // Précédent/Suivant restent ainsi dans ce dossier au lieu de s'arrêter au morceau choisi.
        val audioInCurrentFolder = state.webDavEntries.filter { !it.isDirectory && isAudioFileName(it.name) }
        val queue = audioInCurrentFolder.map { audio ->
            MediaItem.Builder()
                .setMediaId("webdav:${audio.url}")
                .setUri(audio.url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(audio.name.substringBeforeLast('.', audio.name))
                        .setArtist("pCloud · WebDAV")
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build()
                ).build()
        }
        val startIndex = audioInCurrentFolder.indexOfFirst { it.url == entry.url }.coerceAtLeast(0)
        controller?.run {
            setMediaItems(queue, startIndex, 0L)
            prepare()
            play()
        }
    }

    /** Ouvre directement les playlists quand on choisit l'onglet YouTube ou SoundCloud (si connecté). */
    private fun onSourceSelected(source: String) {
        state = state.copy(selectedSource = source, remoteProfileId = if(source=="SMB"||source=="FTP") state.remoteProfileId?.takeIf{id->RemoteProfiles.get(this,id)?.type==if(source=="SMB")"smb" else "ftp"} else null, remotePath=if(source=="SMB"||source=="FTP") state.remotePath else "", remoteEntries=if(source=="SMB"||source=="FTP") state.remoteEntries else emptyList())
        if (state.streamLoading) return
        when (source) {
            StreamSources.YOUTUBE -> if (state.ytLoggedIn) {
                autoLoad(source, state.ytView, state.ytPlaylistTitle, state.ytPlaylists.isEmpty()) { ytShow(it) }
            }
            StreamSources.SOUNDCLOUD -> if (state.scLoggedIn) {
                autoLoad(source, state.scView, state.scPlaylistTitle, state.scPlaylists.isEmpty()) { scShow(it) }
            }
        }
    }

    private fun autoLoad(source: String, view: String, playlistTitle: String?, noPlaylists: Boolean, show: (String) -> Unit) {
        when {
            view == "Recherche" -> Unit
            view == "Playlists" && playlistTitle == null -> if (noPlaylists) show("Playlists")
            source == StreamSources.SOUNDCLOUD && view == "Accueil" && scHomeNeedsRefresh -> show(view)
            state.streamSource != source -> show(view)
        }
    }

    private val scActions = AccountActions(
        openLogin = { state = state.copy(scLoginVisible = true) },
        closeLogin = { state = state.copy(scLoginVisible = false) },
        onCookie = { cookie -> scOnCookie(cookie) },
        logout = { scLogout(null) },
        show = { view -> scShow(view) },
        openPlaylist = { id, title -> scOpenPlaylist(id, title) },
    )

    private fun scOnCookie(cookie: String) {
        val raw = Regex("(?:^|;\\s*)oauth_token=([^;]+)").find(cookie)?.groupValues?.get(1) ?: return
        // Le '+' dans une valeur de cookie est littéral, contrairement à un formulaire URL.
        val token = java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        if (token.isBlank()) return
        if (token != ScAuth.token) { ScAuth.save(this, token); SoundCloudClient.reset() }
        if (state.scLoggedIn) { state = state.copy(scLoginVisible = false); return }
        state = state.copy(scLoggedIn = true, scLoginVisible = false)
        scShow("Accueil")
    }

    private fun scLogout(message: String?) {
        ScAuth.clear(this)
        SoundCloudClient.reset()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        state = state.copy(
            scLoggedIn = false, scLoginVisible = false, scView = "Accueil", scPlaylists = emptyList(),
            scPlaylistTitle = null, streamSource = "", streamResults = emptyList(),
            streamLoading = false, streamError = message,
        )
    }

    private fun scRun(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (e: ScAuthException) {
                // Garder oauth_token; seule l'action Déconnexion explicite efface la session.
                state = state.copy(streamLoading = false, streamError = e.message ?: "Session SoundCloud à renouveler")
            } catch (e: Exception) {
                state = state.copy(streamLoading = false, streamError = e.message ?: "Erreur SoundCloud")
            }
        }
    }

    private fun scShow(view: String) {
        val enabledViews = listOf("Accueil", "Uploads", "Playlists", "Historique", "Aimés").filterNot { it in state.disabledSubTabs }
        val selectedView = if (view != "Recherche" && view in state.disabledSubTabs) enabledViews.firstOrNull() ?: return else view
        if (selectedView == "Recherche") {
            state = state.copy(scView = selectedView, scPlaylistTitle = null, artistProfileTitle = null, localPlaylistTitle = null, streamSource = "", streamResults = emptyList(), streamError = null, streamLoading = false)
            return
        }
        if (selectedView == "Accueil" && scHomeTracks.isNotEmpty() && !scHomeNeedsRefresh) {
            state = state.copy(scView = selectedView, scPlaylistTitle = null, artistProfileTitle = null, localPlaylistTitle = null,
                streamSource = StreamSources.SOUNDCLOUD, streamLoading = false, streamError = null, streamResults = scHomeTracks)
            return
        }
        if (selectedView == "Accueil" && scHomeNeedsRefresh) {
            scHomeTracks = emptyList()
            scHomeNeedsRefresh = false
        }
        state = state.copy(
            scView = selectedView, scPlaylistTitle = null, artistProfileTitle = null, localPlaylistTitle = null, streamSource = StreamSources.SOUNDCLOUD,
            streamLoading = true, streamError = null, streamResults = emptyList(),
        )
        scRun {
            when (selectedView) {
                "Accueil" -> {
                    val home = withContext(Dispatchers.IO) { SoundCloudClient.home() }
                    scHomeTracks = home
                    state = state.copy(streamResults = home, streamLoading = false)
                }
                "Uploads" -> state = state.copy(streamResults = withContext(Dispatchers.IO) { SoundCloudClient.uploads() }, streamLoading = false)
                "Historique" -> state = state.copy(streamResults = withContext(Dispatchers.IO) { SoundCloudClient.history() }, streamLoading = false)
                "Aimés" -> state = state.copy(streamResults = withContext(Dispatchers.IO) { SoundCloudClient.likes() }, streamLoading = false)
                "Playlists" -> {
                    var items = withContext(Dispatchers.IO) { SoundCloudClient.playlists() }
                    if (items.isEmpty()) items = withContext(Dispatchers.IO) { SoundCloudClient.playlists() }
                    state = state.copy(scPlaylists = items, streamLoading = false,
                        streamError = if (items.isEmpty()) "Aucune playlist SoundCloud renvoyée; réessaie." else null)
                }
            }
        }
    }

    private fun scOpenPlaylist(id: String, title: String) {
        state = state.copy(
            scPlaylistTitle = title, streamSource = StreamSources.SOUNDCLOUD,
            streamLoading = true, streamError = null, streamResults = emptyList(),
        )
        scRun {
            state = state.copy(streamResults = withContext(Dispatchers.IO) { SoundCloudClient.playlistTracks(id) }, streamLoading = false)
        }
    }

    private val ytActions = AccountActions(
        openLogin = { state = state.copy(ytLoginVisible = true) },
        closeLogin = { state = state.copy(ytLoginVisible = false) },
        onCookie = { cookie -> ytOnCookie(cookie) },
        logout = { ytLogout(null) },
        show = { view -> ytShow(view) },
        openPlaylist = { id, title -> ytOpenPlaylist(id, title) },
    )

    private fun ytOnCookie(cookie: String) {
        if (state.ytLoggedIn) { state = state.copy(ytLoginVisible = false); return }
        YtAuth.save(this, cookie)
        state = state.copy(ytLoggedIn = true, ytLoginVisible = false)
        ytShow("Accueil")
    }

    private fun ytLogout(message: String?) {
        YtAuth.clear(this)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        state = state.copy(
            ytLoggedIn = false, ytLoginVisible = false, ytView = "Playlists", ytPlaylists = emptyList(),
            ytPlaylistTitle = null, streamSource = "", streamResults = emptyList(),
            streamLoading = false, streamError = message,
        )
    }

    private fun ytRun(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (e: YtAuthException) {
                // YouTube peut renvoyer 401/403 si son cookie a expiré ou si l'API
                // répond transitoirement. Conserver la session et demander une reconnexion.
                state = state.copy(streamLoading = false, streamError = e.message ?: "Session YouTube à renouveler")
            } catch (e: Exception) {
                state = state.copy(streamLoading = false, streamError = e.message ?: "Erreur YouTube Music")
            }
        }
    }

    private fun ytShow(view: String) {
        val enabledViews = listOf("Accueil", "Playlists", "Uploads", "Historique", "Aimés").filterNot { it in state.disabledSubTabs }
        val selectedView = if (view != "Recherche" && view in state.disabledSubTabs) enabledViews.firstOrNull() ?: return else view
        if (selectedView == "Recherche") {
            state = state.copy(ytView = selectedView, ytPlaylistTitle = null, artistProfileTitle = null, localPlaylistTitle = null, streamSource = "", streamResults = emptyList(), streamError = null, streamLoading = false)
            return
        }
        if (selectedView == "Accueil" && ytHomeTracks.isNotEmpty()) {
            state = state.copy(ytView = selectedView, ytPlaylistTitle = null, artistProfileTitle = null, localPlaylistTitle = null,
                streamSource = StreamSources.YOUTUBE, streamLoading = false, streamError = null, streamResults = ytHomeTracks.toList())
            return
        }
        state = state.copy(
            ytView = selectedView, ytPlaylistTitle = null, artistProfileTitle = null, localPlaylistTitle = null, streamSource = StreamSources.YOUTUBE,
            streamLoading = true, streamError = null, streamResults = if (selectedView == "Accueil") ytHomeTracks else emptyList(),
        )
        ytRun {
            when (selectedView) {
                "Accueil" -> {
                    var home = withContext(Dispatchers.IO) { YtMusicClient.home() }
                    if (home.tracks.isEmpty()) home = withContext(Dispatchers.IO) { YtMusicClient.home() }
                    ytHomeTracks = home.tracks.filter(::isIndividualYouTubeVideo).distinctBy { it.mediaId }
                    state = state.copy(streamResults = ytHomeTracks, streamLoading = false,
                        streamError = if (ytHomeTracks.isEmpty()) "Aucun résultat YouTube; réessaie." else null)
                }
                "Historique" -> {
                    val items = withContext(Dispatchers.IO) { YtMusicClient.history() }
                    state = state.copy(streamResults = items, streamLoading = false, streamError = null)
                }
                "Aimés" -> {
                    var items = withContext(Dispatchers.IO) { YtMusicClient.likedSongs() }
                    if (items.isEmpty()) items = withContext(Dispatchers.IO) { YtMusicClient.likedSongs() }
                    state = state.copy(streamResults = items, streamLoading = false)
                }
                "Playlists" -> {
                    var items = withContext(Dispatchers.IO) { YtMusicClient.playlists() }
                    if (items.isEmpty()) items = withContext(Dispatchers.IO) { YtMusicClient.playlists() }
                    state = state.copy(ytPlaylists = items, streamLoading = false,
                        streamError = if (items.isEmpty()) "Aucune playlist YouTube renvoyée; réessaie." else null)
                }
                "Uploads" -> {
                    // Une réponse tardive ne doit jamais déposer une erreur Uploads dans l'Historique.
                    try {
                        val items = withContext(Dispatchers.IO) { YtMusicClient.uploads() }
                        if (state.ytView == "Uploads" && state.streamSource == StreamSources.YOUTUBE) {
                            state = state.copy(streamResults = items, streamLoading = false,
                                streamError = if (items.isEmpty()) "Aucune vidéo d'upload trouvée. Vérifie la chaîne du compte connecté." else null)
                        }
                    } catch (e: Exception) {
                        if (state.ytView == "Uploads" && state.streamSource == StreamSources.YOUTUBE) {
                            state = state.copy(streamLoading = false, streamError = e.message ?: "Uploads YouTube indisponibles")
                        }
                    }
                }
            }
        }
    }

    private fun ytOpenPlaylist(id: String, title: String) {
        state = state.copy(
            ytPlaylistTitle = title, streamSource = StreamSources.YOUTUBE,
            streamLoading = true, streamError = null, streamResults = emptyList(),
        )
        ytRun {
            state = state.copy(streamResults = withContext(Dispatchers.IO) { YtMusicClient.playlistTracks(id) }, streamLoading = false)
        }
    }

    private fun searchStreams(source: String, query: String) {
        state = state.copy(
            streamSource = source, streamLoading = true, streamError = null, streamResults = emptyList(),
            ytView = if (source == StreamSources.YOUTUBE) "Recherche" else state.ytView,
            ytPlaylistTitle = if (source == StreamSources.YOUTUBE) null else state.ytPlaylistTitle,
            scView = if (source == StreamSources.SOUNDCLOUD) "Recherche" else state.scView,
            scPlaylistTitle = if (source == StreamSources.SOUNDCLOUD) null else state.scPlaylistTitle,
        )
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { StreamSources.search(source, query) }
                state = state.copy(streamResults = items, streamLoading = false)
            } catch (e: Exception) {
                state = state.copy(streamLoading = false, streamError = e.message ?: "Recherche impossible")
            }
        }
    }

    private fun openArtistProfile(item: MediaItem) {
        artistProfileReturnState = state
        val extras = item.mediaMetadata.extras
        val profileUrl = extras?.getString("creator_profile_url") ?: extras?.getString("creator_permalink")
        val browseId = extras?.getString("creator_browse_id") ?: profileUrl?.let { Regex("(?:/channel/|/browse/)([A-Za-z0-9_-]+)").find(it)?.groupValues?.get(1) }
        val creatorId = extras?.getString("creator_id")
        val source = when {
            !profileUrl.isNullOrBlank() && profileUrl.contains("soundcloud.com", ignoreCase = true) -> StreamSources.SOUNDCLOUD
            !creatorId.isNullOrBlank() -> StreamSources.SOUNDCLOUD
            !browseId.isNullOrBlank() -> StreamSources.YOUTUBE
            item.mediaId.contains("soundcloud.com", ignoreCase = true) -> StreamSources.SOUNDCLOUD
            else -> StreamSources.YOUTUBE
        }
        val title = item.mediaMetadata.artist?.toString()?.ifBlank { "Artiste" } ?: "Artiste"
        state = state.copy(streamLoading = true, streamError = null, streamResults = emptyList(), artistProfileTitle = title,
            artistProfileVisible = true, localPlaylistTitle = null, ytPlaylistTitle = null, scPlaylistTitle = null,
            streamSource = source, selectedSource = source, isNowPlayingExpanded = true)
        loadArtistProfile(source, creatorId, profileUrl, browseId)
    }

    private fun closeArtistProfile() {
        val previous = artistProfileReturnState
        artistProfileReturnState = null
        state = if (previous == null) state.copy(artistProfileVisible = false, artistProfileTitle = null,
            streamResults = emptyList(), streamError = null, streamLoading = false)
        else state.copy(artistProfileVisible = false, artistProfileTitle = previous.artistProfileTitle,
            streamResults = previous.streamResults, streamError = previous.streamError, streamLoading = previous.streamLoading,
            streamSource = previous.streamSource, selectedSource = previous.selectedSource, ytView = previous.ytView,
            ytPlaylistTitle = previous.ytPlaylistTitle, scView = previous.scView, scPlaylistTitle = previous.scPlaylistTitle,
            localPlaylistTitle = previous.localPlaylistTitle)
    }

    private fun loadArtistProfile(source: String, creatorId: String?, profileUrl: String?, browseId: String?) {
        lifecycleScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) {
                    if (source == StreamSources.SOUNDCLOUD) SoundCloudClient.artistTracks(creatorId, profileUrl)
                    else StreamSources.youtubeChannelTracks(profileUrl, browseId, state.artistProfileTitle)
                }
                state = state.copy(streamResults = tracks, streamLoading = false, streamError = null)
            } catch (e: Exception) {
                state = state.copy(streamLoading = false, streamError = e.message ?: "Profil indisponible. Vérifie la connexion puis réessaie.")
            }
        }
    }

    private fun playArtistProfileTrack(index: Int) {
        val tracks = state.streamResults
        if (index !in tracks.indices) return
        controller?.run {
            setMediaItems(tracks, index, 0L)
            prepare()
            play()
        }
    }

    private fun openLocalPlaylist(name: String) {
        state = state.copy(localPlaylistTitle = name, artistProfileTitle = null, streamLoading = false,
            streamResults = LocalPlaylists.tracks(this, name), streamSource = StreamSources.SOUNDCLOUD)
    }

    private fun loadPlaylistPicker(source: String) {
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    if (source == StreamSources.YOUTUBE) YtMusicClient.playlists() else SoundCloudClient.playlists()
                }
                state = if (source == StreamSources.YOUTUBE) state.copy(ytPlaylists = items)
                    else state.copy(scPlaylists = items)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Chargement des playlists impossible", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshQueue() {
        val c = controller ?: return
        state = state.copy(queueTracks = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) })
    }

    private fun currentYouTubeVideoId(): String? {
        val item = state.current ?: return null
        if (!item.mediaId.startsWith(StreamSources.ID_PREFIX)) return null
        val uri = android.net.Uri.parse(item.mediaId.removePrefix(StreamSources.ID_PREFIX))
        if (uri.host?.endsWith("youtube.com") != true && uri.host != "youtu.be") return null
        val videoId = uri.getQueryParameter("v") ?: uri.lastPathSegment?.takeIf { uri.host == "youtu.be" }
        return videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    private fun createYtPlaylist(title: String) {
        val videoId = currentYouTubeVideoId()
        lifecycleScope.launch {
            try {
                val created = withContext(Dispatchers.IO) { YtMusicClient.createPlaylist(title, videoId) }
                val refreshed = runCatching { withContext(Dispatchers.IO) { YtMusicClient.playlists() } }.getOrNull()
                state = state.copy(ytPlaylists = refreshed ?: (state.ytPlaylists + created).distinctBy { it.id })
                Toast.makeText(this@MainActivity, "Playlist créée avec le titre ajouté", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Création impossible", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addCurrentTrackToYtPlaylist(playlistId: String) {
        val videoId = currentYouTubeVideoId() ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { YtMusicClient.addToPlaylist(playlistId, videoId) }
                Toast.makeText(this@MainActivity, "Titre ajouté à la playlist", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Ajout impossible", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isIndividualYouTubeVideo(item: MediaItem): Boolean {
        val url = item.mediaId.removePrefix(StreamSources.ID_PREFIX)
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val videoId = uri.getQueryParameter("v") ?: return false
        // Les identifiants de playlist/mix (RD/PL/OLAK...) ne sont pas des videoId.
        return videoId.matches(Regex("[A-Za-z0-9_-]{11}")) &&
            !videoId.startsWith("RD") && !videoId.startsWith("PL") && !videoId.startsWith("OLAK")
    }

    private fun playStream(index: Int) {
        val selected = state.streamResults.getOrNull(index) ?: return
        val source = state.selectedSource
        val isYtHome = source == StreamSources.YOUTUBE && state.ytView == "Accueil" && state.ytPlaylistTitle == null
        val isScHome = source == StreamSources.SOUNDCLOUD && state.scView == "Accueil" && state.scPlaylistTitle == null
        if (!isYtHome && !isScHome) {
            controller?.run { setMediaItems(state.streamResults, index, 0L); prepare(); play() }
            return
        }
        if (isScHome) {
            val home = scHomeTracks.ifEmpty { state.streamResults }.toList()
            scHomeNeedsRefresh = true
            controller?.run { setMediaItem(selected); prepare(); play() } ?: return
            lifecycleScope.launch {
                val related = runCatching { withContext(Dispatchers.IO) { SoundCloudClient.relatedTracks(selected) } }
                val relatedItems = related.getOrNull().orEmpty().distinctBy { it.mediaId }
                val queue = (if (relatedItems.size >= 2) relatedItems else home).distinctBy { it.mediaId }
                val c = controller
                if (c != null && c.currentMediaItem?.mediaId == selected.mediaId) {
                    val upcoming = queue.filterNot { it.mediaId == selected.mediaId }
                    if (upcoming.isNotEmpty()) c.addMediaItems(c.currentMediaItemIndex + 1, upcoming)
                    refreshQueue()
                }
            }
            return
        }
        val id = android.net.Uri.parse(selected.mediaId.removePrefix(StreamSources.ID_PREFIX)).getQueryParameter("v")
        if (id.isNullOrBlank()) {
            controller?.run { setMediaItems(state.streamResults, index, 0L); prepare(); play() }
            return
        }
        // Lecture de la sélection immédiate, la liste affichée reste le cache accueil complet.
        controller?.run { setMediaItem(selected); prepare(); play() } ?: return
        lifecycleScope.launch {
            val related = runCatching { withContext(Dispatchers.IO) { YtMusicClient.relatedTracks(id) } }
            val mix = related.getOrNull()?.filter(::isIndividualYouTubeVideo)?.distinctBy { it.mediaId }.orEmpty()
            val queue = if (related.isFailure || mix.size < 2) ytHomeTracks else mix
            val c = controller
            val currentId = c?.currentMediaItem?.mediaId
            if (c != null && currentId == selected.mediaId) {
                val additional = queue.filterNot { it.mediaId == selected.mediaId }
                if (additional.isNotEmpty()) c.addMediaItems(c.currentMediaItemIndex + 1, additional)
            }
            refreshQueue()
        }
    }

    private data class DownloadedAudio(val file: java.io.File, val extension: String, val mime: String, val totalBytes: Long)

    private fun fetchBytesWithRetry(url: String): ByteArray {
        var last: Exception? = null
        for (attempt in 0..3) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 45_000; readTimeout = 180_000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
                }
                try {
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} lors du téléchargement")
                    val data = c.inputStream.use { it.readBytes() }
                    if (data.isEmpty()) throw IOException("Réponse audio vide")
                    return data
                } finally { c.disconnect() }
            } catch (e: Exception) {
                last = e
                if (attempt < 3) Thread.sleep(500L shl attempt)
            }
        }
        throw IOException("Échec après plusieurs tentatives réseau", last)
    }

    private fun downloadHlsToFile(playlistUrl: String, output: java.io.OutputStream): Long {
        var playlist = playlistUrl
        var text = fetchBytesWithRetry(playlist).toString(Charsets.UTF_8)
        if ("#EXT-X-STREAM-INF" in text) {
            val lines = text.lineSequence().map(String::trim).toList()
            val child = lines.indices.firstOrNull { lines[it].startsWith("#EXT-X-STREAM-INF") }
                ?.let { i -> lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } }
                ?: lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: throw IOException("Playlist HLS master sans variante")
            playlist = URL(URL(playlist), child).toString()
            text = fetchBytesWithRetry(playlist).toString(Charsets.UTF_8)
        }
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("Playlist HLS invalide")
        if ("#EXT-X-KEY" in text && !Regex("METHOD=NONE").containsMatchIn(text)) throw IOException("Flux HLS chiffré non pris en charge")
        val segments = text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { URL(URL(playlist), it).toString() }.toList()
        if (segments.isEmpty() || "#EXT-X-ENDLIST" !in text) throw IOException("Playlist HLS incomplète ou live")
        var total = 0L
        // Les variantes fragmented-MP4 exigent le segment d'initialisation avant les médias.
        val mapLine = text.lineSequence().map(String::trim).firstOrNull { it.startsWith("#EXT-X-MAP:") }
        if (mapLine != null) {
            val uri = Regex("URI=\"([^\"]+)\"").find(mapLine)?.groupValues?.get(1)
                ?: throw IOException("Segment d'initialisation HLS mal formé")
            val init = fetchBytesWithRetry(URL(URL(playlist), uri).toString())
            output.write(init); total += init.size
        }
        // Écriture dans l'ordre de la playlist; toute erreur invalide ce candidat et déclenche le fallback.
        segments.forEachIndexed { index, segment ->
            val bytes = fetchBytesWithRetry(segment)
            output.write(bytes); total += bytes.size
            reportDownloadProgress((index + 1).toFloat() / segments.size.toFloat())
        }
        output.flush()
        if (total <= 0L) throw IOException("Flux HLS vide")
        return total
    }

    private fun isAudioFileName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "aiff", "aif", "ape", "alac", "amr", "mid", "midi", "mp2", "wv"
    )

    private fun launchEqualizer() {
        val sessionId = settings.getInt("audio_session_id", 0)
        val intent = android.content.Intent("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL").apply {
            putExtra("android.media.extra.AUDIO_SESSION", sessionId)
            putExtra("android.media.extra.PACKAGE_NAME", packageName)
            putExtra("android.media.extra.CONTENT_TYPE", 0) // AudioEffect.CONTENT_TYPE_MUSIC
        }
        try {
            if (intent.resolveActivity(packageManager) == null) throw android.content.ActivityNotFoundException()
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Aucun égaliseur trouvé", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reportDownloadProgress(progress: Float?) {
        runOnUiThread { state = state.copy(downloadProgress = progress) }
    }

    private fun downloadStreamTrack(item: MediaItem, destination: String = "local") {
        val pageUrl = item.mediaId.removePrefix(StreamSources.ID_PREFIX)
        val host = Uri.parse(pageUrl).host?.lowercase(Locale.ROOT).orEmpty()
        val isYoutube = host == "youtu.be" || host.endsWith("youtube.com")
        val isSoundCloud = host == "soundcloud.com" || host.endsWith(".soundcloud.com")
        if (!isYoutube && !isSoundCloud) {
            Toast.makeText(this, "Téléchargement disponible uniquement pour YouTube et SoundCloud", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Téléchargement audio en cours…", Toast.LENGTH_SHORT).show()
        reportDownloadProgress(0f)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val candidates = if (isSoundCloud) SoundCloudClient.downloadStreams(pageUrl).map { Triple(it.url, it.extension, it.protocol) }
                        else {
                            val source = runCatching { YtMusicClient.resolveDownloadAudioStream(pageUrl, true) }.getOrNull()
                            if (source != null) listOf(Triple(source.url, source.mimeType, "direct")) else {
                                val first = StreamSources.resolveAudioUrl(pageUrl)
                                listOf(Triple(first, "", "direct"))
                            }
                        }
                    var lastError: Exception? = null
                    var completed: DownloadedAudio? = null
                    for ((streamUrl, preferredExt, protocol) in candidates) {
                        var candidateDone = false
                        for (downloadAttempt in 0..2) {
                        val temp = java.io.File.createTempFile("lecteuraudio_", ".part", cacheDir)
                        try {
                            val c = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 45_000; readTimeout = 180_000; instanceFollowRedirects = true
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
                            }
                            var mime = ""
                            var ext = when (preferredExt.substringBefore(';').lowercase(Locale.ROOT)) {
                                "audio/webm" -> "webm"; "audio/opus" -> "opus"; "audio/ogg" -> "ogg"; "audio/mpeg" -> "mp3"; "audio/aac" -> "aac"; "audio/mp4" -> "m4a"; else -> preferredExt
                            }
                            var size: Long
                            try {
                                c.connect()
                                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                                mime = c.contentType?.substringBefore(';')?.lowercase(Locale.ROOT).orEmpty()
                                val pathExt = Uri.parse(c.url.toString()).lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
                                val isHls = protocol == "hls" || pathExt == "m3u8" || mime.contains("mpegurl") || mime.contains("vnd.apple.mpegurl")
                                if (ext.isBlank() || ext.startsWith("audio/")) ext = when {
                                    mime.contains("webm") || pathExt == "webm" -> "webm"
                                    mime.contains("opus") -> "opus"
                                    mime.contains("ogg") || pathExt == "ogg" -> "ogg"
                                    mime.contains("mpeg") || pathExt == "mp3" -> "mp3"
                                    mime.contains("aac") || pathExt == "aac" -> "aac"
                                    mime.contains("mp4") || pathExt in setOf("m4a", "mp4") -> "m4a"
                                    else -> "m4a" // URL signée sans suffixe
                                }
                                size = temp.outputStream().buffered().use { out ->
                                    if (isHls) downloadHlsToFile(c.url.toString(), out)
                                    else {
                                        var copied = 0L
                                        val buffer = ByteArray(128 * 1024)
                                        c.inputStream.use { input ->
                                            while (true) {
                                                val n = input.read(buffer); if (n < 0) break
                                                out.write(buffer, 0, n); copied += n
                                                if (c.contentLengthLong > 0L) reportDownloadProgress((copied.toFloat() / c.contentLengthLong).coerceIn(0f, 1f))
                                            }
                                        }
                                        copied
                                    }
                                }
                                if (size <= 0 || temp.length() != size) throw IOException("Téléchargement audio vide ou incomplet")
                                val expected = c.contentLengthLong
                                if (!isHls && expected > 0 && size != expected) throw IOException("Fichier tronqué ($size/$expected octets)")
                            } finally { c.disconnect() }
                            completed = DownloadedAudio(temp, ext, mime, size)
                            candidateDone = true
                            break
                        } catch (e: Exception) {
                            lastError = e
                            temp.delete()
                            if (downloadAttempt < 2) Thread.sleep(500L shl downloadAttempt)
                        }
                        }
                        if (candidateDone) break
                        // Après les retries, réessaie le format suivant (MP3 > Opus HLS > AAC HLS).
                    }
                    val downloaded = completed ?: throw IOException("Tous les formats SoundCloud/YouTube ont échoué", lastError)
                    val baseName = listOf(item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty())
                        .map { it.replace(Regex("""[/\\:*?"<>|\p{Cntrl}]"""), " ").replace(Regex("\\s+"), " ").trim().trim('.') }
                        .filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "audio" }.take(150)
                    val filename = "$baseName.${downloaded.extension}"
                    val storedMime = when (downloaded.extension) {
                        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "opus" -> "audio/opus"; "ogg" -> "audio/ogg"; "webm" -> "audio/webm";
                        else -> downloaded.mime.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
                    }
                    if (destination != "local") {
                        try {
                            RemoteUploads.upload(this@MainActivity, destination, downloaded.file, filename) { sent, total ->
                                reportDownloadProgress(if(total > 0) (sent.toFloat()/total).coerceIn(0f,1f) else null)
                            }
                            downloaded.file.delete()
                            return@runCatching "$filename|format|${downloaded.extension}"
                        } catch(e: Exception) { downloaded.file.delete(); throw e }
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, filename); put(MediaStore.Audio.Media.MIME_TYPE, storedMime)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Downloads"); put(MediaStore.Audio.Media.IS_PENDING, 1)
                        } else {
                            val legacyDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).resolve("Downloads")
                            if (!legacyDir.exists() && !legacyDir.mkdirs()) throw IOException("Création du dossier public Music/Downloads impossible")
                            if (android.content.pm.PackageManager.PERMISSION_GRANTED != checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) throw IOException("Permission d’écriture au stockage refusée")
                            put(MediaStore.Audio.Media.DATA, legacyDir.resolve(filename).absolutePath)
                        }
                    }
                    val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Création du fichier impossible")
                    try {
                        var written = 0L
                        contentResolver.openOutputStream(uri)?.use { out -> downloaded.file.inputStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) { val count = input.read(buffer); if (count < 0) break; out.write(buffer, 0, count); written += count }
                        } } ?: throw IOException("Ouverture du fichier impossible")
                        if (written != downloaded.totalBytes) throw IOException("Fichier final tronqué ($written/${downloaded.totalBytes} octets)")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                        contentResolver.notifyChange(uri, null)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) android.media.MediaScannerConnection.scanFile(this@MainActivity, arrayOf(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).resolve("Downloads/$filename").absolutePath), arrayOf(storedMime), null)
                        downloaded.file.delete()
                        "$filename|format|${downloaded.extension}"
                    } catch (e: Exception) { contentResolver.delete(uri, null, null); downloaded.file.delete(); throw e }
                }
            }
            reportDownloadProgress(null)
            result.onSuccess { value ->
                val actualName = value.substringBefore("|format|")
                loadTracks()
                Toast.makeText(this@MainActivity, "Transfert terminé : $actualName (format ${value.substringAfter("|format|")})", Toast.LENGTH_LONG).show()
            }.onFailure { error -> Toast.makeText(this@MainActivity, "Échec du téléchargement : ${error.message ?: "erreur réseau"}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        state = state.copy(current = c.currentMediaItem, isPlaying = c.isPlaying, duration = c.duration.coerceAtLeast(0L), position = c.currentPosition,
            queueTracks = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) })
    }
}
