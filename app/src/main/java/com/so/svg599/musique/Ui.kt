package com.so.svg599.musique

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.annotation.SuppressLint
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import android.os.Looper
import android.os.Handler
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.delay
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

val ALL_SOURCES = listOf("Tout", "Local", "pCloud", "SMB", "FTP", "YouTube", "SoundCloud")

/** Balayage horizontal : gauche = suivant, droite = précédent (actif seulement si la case des paramètres est cochée). */
@Composable
private fun Modifier.horizontalSwipe(enabled: Boolean, onLeft: () -> Unit, onRight: () -> Unit): Modifier {
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)
    return if (!enabled) this else this.pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (total < -150f) left() else if (total > 150f) right(); total = 0f },
            onDragCancel = { total = 0f },
            onHorizontalDrag = { _, amount -> total += amount },
        )
    }
}

private val Bg = Color(0xFF121212)
private val Card = Color(0xFF2A2A2A)
private val Green = Color(0xFF1DB954)
private val Muted = Color(0xFFB3B3B3)
private val Art = Color(0xFF534AB7)

data class PlayerUiState(
    val selectedSource: String = "Tout",
    val tracks: List<MediaItem> = emptyList(),
    val current: MediaItem? = null,
    val isPlaying: Boolean = false,
    val isNowPlayingExpanded: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val remoteProfiles: List<RemoteProfile> = emptyList(),
    val remoteProfileId: String? = null,
    val remoteEntries: List<RemoteEntry> = emptyList(),
    val remotePath: String = "",
    val remoteLoading: Boolean = false,
    val remoteError: String? = null,
    val webDavConnected: Boolean = false,
    val webDavEntries: List<WebDavEntry> = emptyList(),
    val webDavPath: String = "",
    val webDavRoot: String = "https://ewebdav.pcloud.com",
    val webDavUsername: String = "",
    val webDavLoading: Boolean = false,
    val webDavError: String? = null,
    val accentColorKey: String = "Vert",
    val statusNotificationEnabled: Boolean = true,
    val streamSource: String = "",
    val streamResults: List<MediaItem> = emptyList(),
    val streamLoading: Boolean = false,
    val streamError: String? = null,
    val ytLoggedIn: Boolean = false,
    val ytLoginVisible: Boolean = false,
    val ytView: String = "Playlists",
    val ytPlaylists: List<AccountPlaylist> = emptyList(),
    val ytPlaylistTitle: String? = null,
    val scLoggedIn: Boolean = false,
    val scLoginVisible: Boolean = false,
    val scView: String = "Accueil",
    val scPlaylists: List<AccountPlaylist> = emptyList(),
    val scPlaylistTitle: String? = null,
    val disabledSubTabs: Set<String> = emptySet(),
    val showArtwork: Boolean = false,
    val youtubeVideoEnabled: Boolean = false,
    val videoAspect: String = "fit",
    val downloadProgress: Float? = null,
    val autoCategories: Set<String> = setOf("local", "pcloud", "youtube", "soundcloud"),
    val hiddenAutoSources: Set<String> = emptySet(),
    val gesturesEnabled: Boolean = false,
    val queueSwipeEnabled: Boolean = false,
    val highAudioQuality: Boolean = true,
    val skipSilence: Boolean = false,
    val rememberPlaybackParameters: Boolean = false,
    val queueTracks: List<MediaItem> = emptyList(),
    val hiddenSources: Set<String> = emptySet(),
    val playbackSpeed: Float = 1f,
    val playbackPitch: Float = 1f,
    val artistProfileTitle: String? = null,
    val artistProfileVisible: Boolean = false,
    val localPlaylists: List<String> = emptyList(),
    val localPlaylistTitle: String? = null,
)

@Composable
fun PlayerApp(
    state: PlayerUiState,
    onPlay: (Int) -> Unit,
    onSelectSource: (String) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onWebDavConnect: (String, String, String, Boolean) -> Unit,
    onWebDavBrowse: (String) -> Unit,
    onWebDavBack: () -> Unit,
    onWebDavDisconnect: () -> Unit,
    onPlayRemote: (WebDavEntry) -> Unit,
    onSaveRemoteProfile: (RemoteProfile) -> Unit,
    onSelectRemoteProfile: (String) -> Unit,
    onDeleteRemoteProfile: (String) -> Unit,
    onBrowseRemote: (String) -> Unit,
    onBackRemote: () -> Unit,
    onPlayRemoteEntry: (RemoteEntry) -> Unit,
    onStreamSearch: (String, String) -> Unit,
    onSearchArtist: (String) -> Unit,
    onPlayStream: (Int) -> Unit,
    yt: AccountActions,
    sc: AccountActions,
    onOpenNow: () -> Unit,
    onCloseNow: () -> Unit,
    onThemeChange: (String) -> Unit,
    onNotificationToggle: (Boolean) -> Unit,
    onSubTabVisibilityToggle: (String, Boolean) -> Unit,
    onArtworkToggle: (Boolean) -> Unit,
    onYoutubeVideoToggle: (Boolean) -> Unit,
    onVideoAspectChange: (String) -> Unit,
    onDownloadTrack: (MediaItem, String) -> Unit,
    onAutoCategoryToggle: (String, Boolean) -> Unit,
    onAutoSourceVisibilityToggle: (String, Boolean) -> Unit,
    onGesturesToggle: (Boolean) -> Unit,
    onQueueSwipeToggle: (Boolean) -> Unit,
    onAudioQualityToggle: (Boolean) -> Unit,
    onSkipSilenceToggle: (Boolean) -> Unit,
    onSourceVisibilityToggle: (String, Boolean) -> Unit,
    onPlaybackParametersChange: (Float, Float, Boolean) -> Unit,
    onEqualizer: () -> Unit,
    onOpenArtist: (MediaItem) -> Unit,
    onCloseArtistProfile: () -> Unit,
    onPlayProfileTrack: (Int) -> Unit,
    onOpenQueue: () -> Unit,
    onPlayQueueTrack: (Int) -> Unit,
    onCreateYtPlaylist: (String) -> Unit,
    onAddYtPlaylist: (String) -> Unit,
    onLoadYtPlaylists: () -> Unit,
    onOpenLocalPlaylist: (String) -> Unit,
) {
    val accent = accentFor(state.accentColorKey)
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    fun listStateFor(key: String): LazyListState = listStates.getOrPut(key) { LazyListState() }
    val trackListState = listStateFor("library:${state.selectedSource}")
    val pcloudListState = listStateFor("pcloud:${state.webDavPath}")
    val remoteListState = listStateFor("remote:${state.remoteProfileId}:${state.remotePath}")
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = Bg, surface = Bg)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            if (state.ytLoginVisible) AccountLoginScreen(
                "Connexion YouTube Music", "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F", "https://music.youtube.com",
                { it.contains("SAPISID=") || it.contains("__Secure-3PAPISID=") }, yt.onCookie, yt.closeLogin,
            )
            else if (state.scLoginVisible) AccountLoginScreen(
                "Connexion SoundCloud", "https://soundcloud.com/signin", "https://soundcloud.com",
                { it.contains("oauth_token=") }, sc.onCookie, sc.closeLogin, captureApiToken = true,
            )
            else if (state.artistProfileVisible && state.selectedSource == StreamSources.YOUTUBE) {
                NativeChannelScreen(state, accent, onPlayStream, yt, sc, {}, onOpenLocalPlaylist,
                    listStateFor("stream:${state.selectedSource}:${state.artistProfileTitle}"), onCloseArtistProfile)
            }
            else if (state.isNowPlayingExpanded && state.current != null) {
                Box(Modifier.fillMaxSize()) {
                    NowPlaying(state, onToggle, onNext, onPrev, onSeek, onSeekBy, onCloseNow, onPlaybackParametersChange, onEqualizer, onOpenArtist, onSearchArtist, onOpenQueue, onPlayQueueTrack, onCreateYtPlaylist, onAddYtPlaylist, onLoadYtPlaylists, onYoutubeVideoToggle, onVideoAspectChange, onDownloadTrack)
                    if (state.artistProfileVisible) ArtistProfileOverlay(
                        state = state,
                        accent = accent,
                        onClose = { onCloseArtistProfile() },
                        onRetry = { state.current?.let(onOpenArtist) },
                        onPlay = onPlayProfileTrack,
                    )
                }
            } else Library(state, onPlay, onSelectSource, onToggle, onOpenNow, onWebDavConnect, onWebDavBrowse, onWebDavBack, onWebDavDisconnect, onPlayRemote, onSaveRemoteProfile, onSelectRemoteProfile, onDeleteRemoteProfile, onBrowseRemote, onBackRemote, onPlayRemoteEntry, onThemeChange, onNotificationToggle, onSubTabVisibilityToggle, onArtworkToggle, onAutoCategoryToggle, onAutoSourceVisibilityToggle, onGesturesToggle, onQueueSwipeToggle, onAudioQualityToggle, onSkipSilenceToggle, onVideoAspectChange, onEqualizer, onSourceVisibilityToggle, onStreamSearch, onPlayStream, yt, sc, accent, onOpenArtist, onOpenLocalPlaylist, trackListState, pcloudListState, listStateFor("stream:${state.selectedSource}:${state.artistProfileTitle ?: state.localPlaylistTitle ?: state.ytPlaylistTitle ?: state.scPlaylistTitle ?: state.ytView + "/" + state.scView}"), remoteListState, onCloseArtistProfile)
        }
    }
}

@Composable
private fun NativeChannelScreen(
    state: PlayerUiState,
    accent: Color,
    onPlayStream: (Int) -> Unit,
    yt: AccountActions,
    sc: AccountActions,
    onOpenArtist: (MediaItem) -> Unit,
    onOpenLocalPlaylist: (String) -> Unit,
    listState: LazyListState,
    onCloseArtistProfile: () -> Unit,
) {
    StreamPane(
        source = state.selectedSource,
        state = state,
        accent = accent,
        onPlay = onPlayStream,
        yt = yt,
        sc = sc,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        onOpenArtist = onOpenArtist,
        onOpenLocalPlaylist = onOpenLocalPlaylist,
        trackListState = listState,
        onCloseArtistProfile = onCloseArtistProfile,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Library(
    state: PlayerUiState,
    onPlay: (Int) -> Unit,
    onSelectSource: (String) -> Unit,
    onToggle: () -> Unit,
    onOpenNow: () -> Unit,
    onWebDavConnect: (String, String, String, Boolean) -> Unit,
    onWebDavBrowse: (String) -> Unit,
    onWebDavBack: () -> Unit,
    onWebDavDisconnect: () -> Unit,
    onPlayRemote: (WebDavEntry) -> Unit,
    onSaveRemoteProfile: (RemoteProfile) -> Unit,
    onSelectRemoteProfile: (String) -> Unit,
    onDeleteRemoteProfile: (String) -> Unit,
    onBrowseRemote: (String) -> Unit,
    onBackRemote: () -> Unit,
    onPlayRemoteEntry: (RemoteEntry) -> Unit,
    onThemeChange: (String) -> Unit,
    onNotificationToggle: (Boolean) -> Unit,
    onSubTabVisibilityToggle: (String, Boolean) -> Unit,
    onArtworkToggle: (Boolean) -> Unit,
    onAutoCategoryToggle: (String, Boolean) -> Unit,
    onAutoSourceVisibilityToggle: (String, Boolean) -> Unit,
    onGesturesToggle: (Boolean) -> Unit,
    onQueueSwipeToggle: (Boolean) -> Unit,
    onAudioQualityToggle: (Boolean) -> Unit,
    onSkipSilenceToggle: (Boolean) -> Unit,
    onVideoAspectChange: (String) -> Unit,
    onEqualizer: () -> Unit,
    onSourceVisibilityToggle: (String, Boolean) -> Unit,
    onStreamSearch: (String, String) -> Unit,
    onPlayStream: (Int) -> Unit,
    yt: AccountActions,
    sc: AccountActions,
    accent: Color,
    onOpenArtist: (MediaItem) -> Unit,
    onOpenLocalPlaylist: (String) -> Unit,
    trackListState: LazyListState,
    pcloudListState: LazyListState,
    streamListState: LazyListState,
    remoteListState: LazyListState,
    onCloseArtistProfile: () -> Unit,
) {
    val sources = ALL_SOURCES.filter { it !in state.hiddenSources }
    val isStreamSource = state.selectedSource == "YouTube" || state.selectedSource == "SoundCloud"
    var showLogin by remember { mutableStateOf(false) }
    var showRemoteEditor by remember { mutableStateOf(false) }
    var remoteType by remember { mutableStateOf("smb") }
    var remoteEditingId by remember { mutableStateOf<String?>(null) }
    var remoteContextProfile by remember { mutableStateOf<RemoteProfile?>(null) }
    var remoteDeleteConfirm by remember { mutableStateOf<RemoteProfile?>(null) }
    var remoteRenameProfile by remember { mutableStateOf<RemoteProfile?>(null) }
    var remoteRenameValue by remember { mutableStateOf("") }
    var remoteName by remember { mutableStateOf("") }; var remoteHost by remember { mutableStateOf("") }; var remoteShare by remember { mutableStateOf("") }; var remoteUser by remember { mutableStateOf("") }; var remotePassword by remember { mutableStateOf("") }; var remoteDomain by remember { mutableStateOf("") }; var remotePort by remember { mutableStateOf("21") }; var remotePassive by remember { mutableStateOf(true) }; var remoteFtps by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("https://ewebdav.pcloud.com") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberConnection by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showAutoCategories by remember { mutableStateOf(false) }
    var showSubTabs by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(state.webDavConnected) {
        if (state.webDavConnected) showLogin = false
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().horizontalSwipe(
            state.gesturesEnabled,
            onLeft = { val i = sources.indexOf(state.selectedSource); if (i >= 0 && i < sources.lastIndex) onSelectSource(sources[i + 1]) },
            onRight = { val i = sources.indexOf(state.selectedSource); if (i > 0) onSelectSource(sources[i - 1]) },
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bibliothèque", color = Color.White, fontSize = 24.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { showSearch = !showSearch; if (!showSearch) searchQuery = "" }) {
                Icon(Icons.Default.Search, contentDescription = "Rechercher", tint = Color.White)
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Paramètres", tint = Color.White)
            }
        }
        if (showSearch) {
            var suggestions by remember(state.selectedSource) { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(searchQuery, state.selectedSource, isStreamSource) {
                suggestions = emptyList()
                val q = searchQuery.trim()
                if (isStreamSource && q.length >= 2) {
                    delay(250)
                    suggestions = withContext(Dispatchers.IO) { fetchSearchSuggestions(state.selectedSource, q) }
                }
            }
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(if (isStreamSource) "Rechercher sur ${state.selectedSource}…" else "Titre, artiste, fichier…") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = if (isStreamSource) ImeAction.Search else ImeAction.Default),
                keyboardActions = KeyboardActions(onSearch = { if (isStreamSource && searchQuery.isNotBlank()) onStreamSearch(state.selectedSource, searchQuery.trim()) }),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, contentDescription = "Effacer") } }
            )
            if (suggestions.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions) { suggestion ->
                        AssistChip(onClick = { searchQuery = suggestion; suggestions = emptyList(); onStreamSearch(state.selectedSource, suggestion) }, label = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
        }
        if (state.downloadProgress != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Téléchargement : ${(state.downloadProgress.coerceIn(0f, 1f) * 100).toInt()} %", color = Muted, fontSize = 12.sp)
                LinearProgressIndicator(progress = { state.downloadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources) { s ->
                FilterChip(
                    selected = state.selectedSource == s,
                    onClick = {
                        onSelectSource(s)
                        if (s == "pCloud" && !state.webDavConnected) showLogin = true
                    },
                    label = { Text(s) },
                    colors = FilterChipDefaults.filterChipColors(containerColor = Card, labelColor = Color.White, selectedContainerColor = accent, selectedLabelColor = Color.Black),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when (state.selectedSource) {
            "Tout", "Local" -> {
                val visibleTracks = state.tracks.filter { item -> searchQuery.isBlank() || listOf(item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString()).any { it?.contains(searchQuery, ignoreCase = true) == true } }
                LaunchedEffect(state.isNowPlayingExpanded, state.current?.mediaId, state.selectedSource, searchQuery, visibleTracks.map { it.mediaId }) {
                    if (!state.isNowPlayingExpanded) {
                        val currentIndex = visibleTracks.indexOfFirst { it.mediaId == state.current?.mediaId }
                        if (currentIndex >= 0) trackListState.animateScrollToItem(currentIndex)
                    }
                }
                LazyColumn(state = trackListState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(visibleTracks) { _, item ->
                        val md = item.mediaMetadata
                        Row(Modifier.fillMaxWidth().clickable { onPlay(state.tracks.indexOf(item).coerceAtLeast(0)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ArtworkImage(YouTubeArtwork.resolve(item.mediaId, item.localConfiguration?.uri?.toString(), md.artworkUri), Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)), showArtwork = state.showArtwork, fallbackUri = item.localConfiguration?.uri)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(md.title?.toString() ?: "Sans titre", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(md.artist?.toString() ?: "Artiste inconnu", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            "pCloud" -> {
                if (!state.webDavConnected) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.Cloud, null, tint = accent, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Connexion pCloud via WebDAV", color = Color.White, fontSize = 18.sp)
                        Text("Connecte-toi pour parcourir tes dossiers et lire tes morceaux.", color = Muted)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { showLogin = true }) { Text("Se connecter") }
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.webDavPath.trimEnd('/') != state.webDavRoot.trimEnd('/')) TextButton(onClick = { onWebDavBack() }) { Text("‹ Retour") }
                            Text(state.webDavUsername, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (state.webDavLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.webDavError?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(16.dp)) }
                        val audioEntries = state.webDavEntries.filter { !it.isDirectory && isAudioFile(it.name) }
                        LaunchedEffect(state.current?.mediaId, state.webDavPath, audioEntries.map { it.url }) {
                            val currentUrl = state.current?.mediaId?.removePrefix("webdav:")
                            val audioIndex = audioEntries.indexOfFirst { it.url == currentUrl }
                            if (audioIndex >= 0) {
                                val directoryCount = state.webDavEntries.count { it.isDirectory }
                                pcloudListState.animateScrollToItem(directoryCount + audioIndex)
                            }
                        }
                        LazyColumn(state = pcloudListState) {
                            items(state.webDavEntries.filter { (it.isDirectory || isAudioFile(it.name)) && (searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)) }) { entry ->
                                val playable = !entry.isDirectory && isAudioFile(entry.name)
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        if (entry.isDirectory) onWebDavBrowse(entry.url) else if (playable) onPlayRemote(entry)
                                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.MusicNote, null, tint = if (entry.isDirectory) accent else Muted, modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (entry.isDirectory) "Dossier" else "Audio · appuyer pour lire", color = Muted, fontSize = 12.sp)
                                    }
                                    if (playable) Icon(Icons.Default.PlayArrow, "Lire", tint = accent)
                                }
                            }
                        }
                    }
                }
            }
            "YouTube", "SoundCloud" -> StreamPane(state.selectedSource, state, accent, onPlayStream, yt, sc, Modifier.weight(1f), { _: MediaItem -> Unit }, onOpenLocalPlaylist, streamListState, onCloseArtistProfile)
            "SMB", "FTP" -> {
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.remoteProfileId != null) TextButton(onClick = { onBackRemote() }) { Text("‹ Retour") }
                        Text(state.remoteProfiles.firstOrNull { it.id == state.remoteProfileId }?.name ?: "Profils ${state.selectedSource}", color = Muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { remoteType = if(state.selectedSource=="SMB") "smb" else "ftp"; remoteEditingId=null; remoteName=""; remoteHost=""; remoteShare=""; remoteUser=""; remotePassword=""; remoteDomain=""; remotePort=if(remoteType=="smb")"445" else "21"; remotePassive=true; remoteFtps=false; showRemoteEditor=true }) { Text("+ Profil") }
                    }
                    state.remoteError?.let { Text(it,color=Color(0xFFFF8A80),modifier=Modifier.padding(12.dp)) }
                    if(state.remoteLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(state.remoteProfileId==null) state.remoteProfiles.filter{it.type==if(state.selectedSource=="SMB")"smb" else "ftp"}.forEach { profile ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick={onSelectRemoteProfile(profile.id)}, onLongClick={remoteContextProfile=profile}).padding(16.dp), verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.Cloud, null, tint=accent); Spacer(Modifier.width(12.dp)); Column { Text(profile.name,color=Color.White); Text("${profile.host}${if(profile.type=="smb") "/${profile.share}" else ":${profile.port}"}",color=Muted,fontSize=12.sp) } }
                    } else {
                        val visibleRemoteEntries = state.remoteEntries.filter { it.directory || isAudioFile(it.name) }
                        LaunchedEffect(state.current?.mediaId, state.remotePath, visibleRemoteEntries.map { it.path }) {
                            val idx = visibleRemoteEntries.indexOfFirst { !it.directory && state.current?.mediaId == "${state.remoteProfileId}:${it.path}" }
                            if (idx >= 0) remoteListState.animateScrollToItem(idx)
                        }
                        LazyColumn(state = remoteListState) {
                            items(visibleRemoteEntries) { entry ->
                                val playable = !entry.directory && isAudioFile(entry.name)
                                Row(Modifier.fillMaxWidth().clickable { if (entry.directory) onBrowseRemote(entry.path) else if (playable) onPlayRemoteEntry(entry) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (entry.directory) Icons.Default.Folder else Icons.Default.MusicNote, null, tint = if (entry.directory) accent else Muted, modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (entry.directory) "Dossier" else "Audio · appuyer pour lire", color = Muted, fontSize = 12.sp)
                                    }
                                    if (playable) Icon(Icons.Default.PlayArrow, "Lire", tint = accent)
                                }
                            }
                        }
                    }
                }
            }
            else -> Column(Modifier.weight(1f)) {}
        }
        state.current?.let { cur ->
            Row(Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.22f)).clickable { onOpenNow() }.pointerInput(Unit) { var drag = 0f; detectVerticalDragGestures(onVerticalDrag = { change, amount -> change.consume(); drag += amount }, onDragEnd = { if (drag < -45f) onOpenNow(); drag = 0f }, onDragCancel = { drag = 0f }) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(YouTubeArtwork.resolve(cur.mediaId, cur.localConfiguration?.uri?.toString(), cur.mediaMetadata.artworkUri), Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)), showArtwork = state.showArtwork, fallbackUri = cur.localConfiguration?.uri)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(cur.mediaMetadata.title?.toString() ?: "", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        cur.mediaMetadata.artist?.toString() ?: "",
                        color = Muted,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier,
                    )
                }
                IconButton(onClick = { onToggle() }) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Lecture / pause", tint = Color.White) }
            }
        }
    }

    if (remoteContextProfile != null) AlertDialog(
        onDismissRequest = { remoteContextProfile = null }, title = { Text(remoteContextProfile!!.name) },
        text = { Column { TextButton(onClick = { val p=remoteContextProfile!!; remoteType=p.type; remoteEditingId=p.id; remoteName=p.name; remoteHost=p.host; remoteShare=p.share; remoteUser=p.username; remotePassword=p.password; remoteDomain=p.domain; remotePort=p.port.toString(); remotePassive=p.passive; remoteFtps=p.ftps; remoteContextProfile=null; showRemoteEditor=true }) { Text("Modifier") }; TextButton(onClick = { remoteRenameProfile=remoteContextProfile; remoteRenameValue=remoteContextProfile!!.name; remoteContextProfile=null }) { Text("Renommer") }; TextButton(onClick = { remoteDeleteConfirm=remoteContextProfile; remoteContextProfile=null }) { Text("Supprimer") } } },
        confirmButton = { TextButton(onClick = { remoteContextProfile=null }) { Text("Fermer") } })
    if (remoteRenameProfile != null) AlertDialog(onDismissRequest={remoteRenameProfile=null}, title={Text("Renommer le profil")}, text={OutlinedTextField(remoteRenameValue,{remoteRenameValue=it},label={Text("Nom")},singleLine=true)}, confirmButton={TextButton(enabled=remoteRenameValue.isNotBlank(),onClick={onSaveRemoteProfile(remoteRenameProfile!!.copy(name=remoteRenameValue.trim()));remoteRenameProfile=null}){Text("Renommer")}}, dismissButton={TextButton(onClick={remoteRenameProfile=null}){Text("Annuler")}})
    if (remoteDeleteConfirm != null) AlertDialog(onDismissRequest={remoteDeleteConfirm=null}, title={Text("Supprimer le profil ?")}, text={Text("Le profil « ${remoteDeleteConfirm!!.name} » sera supprimé.")}, confirmButton={TextButton(onClick={onDeleteRemoteProfile(remoteDeleteConfirm!!.id);remoteDeleteConfirm=null}){Text("Supprimer")}}, dismissButton={TextButton(onClick={remoteDeleteConfirm=null}){Text("Annuler")}})
    if(showRemoteEditor) AlertDialog(onDismissRequest={showRemoteEditor=false}, title={Text("${if(remoteEditingId==null)"Nouveau profil" else "Modifier le profil"} ${remoteType.uppercase()}")}, text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(remoteName,{remoteName=it},label={Text("Nom du profil")},singleLine=true); OutlinedTextField(remoteHost,{remoteHost=it},label={Text("Hôte")},singleLine=true)
        if(remoteType=="smb") { OutlinedTextField(remoteShare,{remoteShare=it},label={Text("Partage")},singleLine=true); OutlinedTextField(remoteDomain,{remoteDomain=it},label={Text("Domaine (optionnel)")},singleLine=true) } else { OutlinedTextField(remotePort,{remotePort=it},label={Text("Port")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number)); Row(verticalAlignment=Alignment.CenterVertically){Checkbox(remotePassive,{remotePassive=it});Text("Mode passif")}; Row(verticalAlignment=Alignment.CenterVertically){Checkbox(remoteFtps,{remoteFtps=it});Text("FTPS") } }; OutlinedTextField(remoteUser,{remoteUser=it},label={Text("Utilisateur")},singleLine=true); OutlinedTextField(remotePassword,{remotePassword=it},label={Text("Mot de passe")},singleLine=true,visualTransformation=PasswordVisualTransformation())
    }}, confirmButton={Button(enabled=remoteName.isNotBlank()&&remoteHost.isNotBlank()&&remoteUser.isNotBlank(),onClick={onSaveRemoteProfile(RemoteProfile(remoteEditingId ?: java.util.UUID.randomUUID().toString(),remoteType,remoteName.trim(),remoteHost,remoteShare,remotePort.toIntOrNull()?:21,remoteUser,remotePassword,remoteDomain,remotePassive,remoteFtps));remoteEditingId=null;remoteName="";remoteHost="";remoteShare="";remoteUser="";remotePassword="";remoteDomain="";showRemoteEditor=false}){Text("Enregistrer")}}, dismissButton={TextButton(onClick={showRemoteEditor=false;remoteEditingId=null}){Text("Annuler")}})

    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("Paramètres") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Apparence", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Couleur de l’application", color = Color.White)
                val colors = listOf("Vert", "Bleu", "Violet", "Rouge", "Orange", "Rose")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(colors) { colorName ->
                        val c = accentFor(colorName)
                        FilterChip(
                            selected = state.accentColorKey == colorName,
                            onClick = { onThemeChange(colorName) },
                            label = { Text(colorName) },
                            leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(c)) }
                        )
                    }
                }
                HorizontalDivider()
                Text("Bibliothèques", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().clickable { showCategories = !showCategories }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Catégories Android", color = Color.White, modifier = Modifier.weight(1f))
                    Icon(if (showCategories) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White)
                }
                if (showCategories) {
                    ALL_SOURCES.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = name !in state.hiddenSources, onCheckedChange = { visible -> onSourceVisibilityToggle(name, visible) })
                            Text(name, color = Color.White)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { showAutoCategories = !showAutoCategories }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Catégories Android Auto", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (showAutoCategories) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White)
                }
                if (showAutoCategories) {
                    ALL_SOURCES.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = name !in state.hiddenAutoSources, onCheckedChange = { visible -> onAutoSourceVisibilityToggle(name, visible) })
                            Text(name, color = Color.White)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { showSubTabs = !showSubTabs }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Onglets de la 2ème barre", color = Color.White, modifier = Modifier.weight(1f))
                    Icon(if (showSubTabs) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White)
                }
                if (showSubTabs) {
                    listOf("Accueil" to "Accueil (YouTube)", "Playlists" to "Playlists", "Uploads" to "Uploads (YouTube / SoundCloud)", "Historique" to "Historique", "Aimés" to "Aimés").forEach { (key, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key !in state.disabledSubTabs, onCheckedChange = { onSubTabVisibilityToggle(key, it) })
                            Text(label, color = Color.White)
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Avancés", color = Color.White, modifier = Modifier.weight(1f))
                    Icon(if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White)
                }
                if (showAdvanced) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.showArtwork, onCheckedChange = onArtworkToggle)
                        Text("Afficher les pochettes", color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.statusNotificationEnabled, onCheckedChange = onNotificationToggle)
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text("Icône de notification", color = Color.White)
                            Text("Afficher ou masquer la notification supplémentaire", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Text("Le lecteur multimédia Android peut rester visible pour les commandes de lecture.", color = Muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.gesturesEnabled, onCheckedChange = onGesturesToggle)
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text("Gestes de balayage", color = Color.White)
                            Text("À gauche/droite : changer de catégorie ou de titre. Vers le haut dans le grand lecteur : ouvrir la liste des morceaux suivants.", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Text("Aspect de la vidéo", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("fit" to "Ajuster", "fill" to "Zoom").forEach { (key, label) ->
                            FilterChip(selected = state.videoAspect == key, onClick = { onVideoAspectChange(key) }, label = { Text(label, maxLines = 1) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.skipSilence, onCheckedChange = onSkipSilenceToggle)
                        Column {
                            Text("Ignorer les moments de silence", color = Color.White)
                            Text("Appliqué uniquement aux lectures YouTube", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Qualité audio", color = Color.White, modifier = Modifier.weight(1f))
                        FilterChip(selected = state.highAudioQuality, onClick = { onAudioQualityToggle(true) }, label = { Text("Haute") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = !state.highAudioQuality, onClick = { onAudioQualityToggle(false) }, label = { Text("Basse") })
                    }
                    Text(if (state.highAudioQuality) "Cible : environ 160 kbps" else "Cible : environ 48 kbps", color = Muted, fontSize = 12.sp)
                    val lastError = LocalContext.current.getSharedPreferences("player_settings", android.content.Context.MODE_PRIVATE).getString("last_error", null)
                    Text("Dernière erreur de lecture", color = Color.White)
                    Text(lastError ?: "Aucune", color = Muted, fontSize = 12.sp)
                }
                if (state.webDavConnected) {
                    HorizontalDivider()
                    Text("Connexion pCloud", color = Color.White)
                    Text(state.webDavUsername, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OutlinedButton(
                        onClick = {
                            onWebDavDisconnect()
                            showSettings = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80))
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Déconnecter pCloud")
                    }
                }
                HorizontalDivider()
                Text("À propos", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Musique • v1.3", color = Muted, fontSize = 13.sp)
                val aboutContext = LocalContext.current
                val openDiscord = {
                    runCatching { aboutContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/HSHtDYFzeD"))) }
                        .onFailure { Toast.makeText(aboutContext, "Impossible d’ouvrir le lien", Toast.LENGTH_SHORT).show() }
                    Unit
                }
                val openGithub = {
                    runCatching { aboutContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/so-svg599"))) }
                        .onFailure { Toast.makeText(aboutContext, "Impossible d’ouvrir le lien", Toast.LENGTH_SHORT).show() }
                    Unit
                }
                Text("Join Discord for updates!", color = accent, fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = { openDiscord() }).padding(vertical = 3.dp))
                Text("My Github", color = accent, fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = { openGithub() }).padding(vertical = 3.dp))
                HorizontalDivider()
                Text("YouTube Music", color = Color.White)
                if (state.ytLoggedIn) {
                    Text("Connecté : playlists, historique et titres aimés disponibles", color = Muted, fontSize = 13.sp)
                    OutlinedButton(
                        onClick = { yt.logout(); showSettings = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80))
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Déconnecter YouTube Music")
                    }
                } else {
                    Text("Connecte-toi pour retrouver tes playlists et ton historique.", color = Muted, fontSize = 13.sp)
                    Button(
                        onClick = { yt.openLogin(); showSettings = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Se connecter à YouTube Music")
                    }
                }
                HorizontalDivider()
                Text("SoundCloud", color = Color.White)
                if (state.scLoggedIn) {
                    Text("Connecté : playlists, historique et titres likés disponibles", color = Muted, fontSize = 13.sp)
                    OutlinedButton(
                        onClick = { sc.logout(); showSettings = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80))
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Déconnecter SoundCloud")
                    }
                } else {
                    Text("Connecte-toi pour retrouver tes playlists, likes et historique.", color = Muted, fontSize = 13.sp)
                    Button(
                        onClick = { sc.openLogin(); showSettings = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Se connecter à SoundCloud")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Fermer") } }
    )

    if (showLogin) AlertDialog(
        onDismissRequest = { showLogin = false },
        title = { Text("Connexion pCloud · WebDAV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choisis le serveur correspondant à la région de ton compte.", fontSize = 13.sp)
                var serverChoice by remember { mutableStateOf("Europe") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = serverChoice == "Europe", onClick = { serverChoice = "Europe"; server = "https://ewebdav.pcloud.com" })
                    Text("Europe", modifier = Modifier.clickable { serverChoice = "Europe"; server = "https://ewebdav.pcloud.com" })
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = serverChoice == "USA", onClick = { serverChoice = "USA"; server = "https://webdav.pcloud.com" })
                    Text("USA", modifier = Modifier.clickable { serverChoice = "USA"; server = "https://webdav.pcloud.com" })
                }
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("E-mail pCloud") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Mot de passe pCloud") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberConnection, onCheckedChange = { rememberConnection = it })
                    Text("Mémoriser la connexion sur cet appareil", color = Color.White, fontSize = 13.sp)
                }
                state.webDavError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { Button(enabled = email.isNotBlank() && password.isNotEmpty() && !state.webDavLoading, onClick = { onWebDavConnect(server, email.trim(), password, rememberConnection); password = "" }) { Text(if (state.webDavLoading) "Connexion…" else "Connecter") } },
        dismissButton = { TextButton(onClick = { showLogin = false }) { Text("Annuler") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlaying(state: PlayerUiState, onToggle: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit, onSeek: (Long) -> Unit, onSeekBy: (Long) -> Unit, onClose: () -> Unit, onPlaybackParametersChange: (Float, Float, Boolean) -> Unit, onEqualizer: () -> Unit, onOpenArtist: (MediaItem) -> Unit, onSearchArtist: (String) -> Unit, onOpenQueue: () -> Unit, onPlayQueueTrack: (Int) -> Unit, onCreateYtPlaylist: (String) -> Unit, onAddYtPlaylist: (String) -> Unit, onLoadYtPlaylists: () -> Unit, onYoutubeVideoToggle: (Boolean) -> Unit, onVideoAspectChange: (String) -> Unit, onDownloadTrack: (MediaItem, String) -> Unit) {
    val md = state.current!!.mediaMetadata
    val creatorExtras = md.extras
    val hasCreatorProfile = !creatorExtras?.getString("creator_profile_url").isNullOrBlank() ||
        !creatorExtras?.getString("creator_permalink").isNullOrBlank() ||
        !creatorExtras?.getString("creator_browse_id").isNullOrBlank() ||
        !creatorExtras?.getString("creator_id").isNullOrBlank()
    val accent = accentFor(state.accentColorKey)
    var showAudioTuning by remember { mutableStateOf(false) }
    var showPlayerMenu by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var queueRequested by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showDownloadChoices by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var speed by remember(state.playbackSpeed) { mutableFloatStateOf(state.playbackSpeed) }
    var pitch by remember(state.playbackPitch) { mutableFloatStateOf(state.playbackPitch) }
    var rememberParameters by remember(state.rememberPlaybackParameters) { mutableStateOf(state.rememberPlaybackParameters) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Color(0xFF101318)).horizontalSwipe(state.gesturesEnabled, onLeft = onNext, onRight = onPrev).statusBarsPadding().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 68.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onClose() }) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Réduire", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { showPlayerMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu du lecteur", tint = accent) }
                DropdownMenu(expanded = showPlayerMenu, onDismissRequest = { showPlayerMenu = false }) {
                    DropdownMenuItem(text = { Text("Vitesse et pitch") }, leadingIcon = { Icon(Icons.Default.Tune, null) }, onClick = { showPlayerMenu = false; showAudioTuning = true })
                    DropdownMenuItem(text = { Text("Égaliseur") }, leadingIcon = { Icon(Icons.Default.GraphicEq, null) }, onClick = { showPlayerMenu = false; onEqualizer() })
                    val currentItem = state.current
                    val currentMediaId = currentItem?.mediaId.orEmpty()
                    val youtubeTrack = currentMediaId.startsWith("stream:") && (currentMediaId.contains("youtube.com/", true) || currentMediaId.contains("youtu.be/", true))
                    val streamTrack = currentMediaId.startsWith("stream:") && (currentMediaId.contains("youtube.com/", true) || currentMediaId.contains("youtu.be/", true) || currentMediaId.contains("soundcloud.com/", true))
                    if (streamTrack && currentItem != null) {
                        DropdownMenuItem(text = { Text("Télécharger le titre") }, leadingIcon = { Icon(Icons.Default.FileDownload, null) }, onClick = { showPlayerMenu = false; showDownloadChoices = true })
                    }
                    if (youtubeTrack) {
                        DropdownMenuItem(text = { Text(if (state.youtubeVideoEnabled) "Désactiver la vidéo YouTube" else "Activer la vidéo YouTube") }, leadingIcon = { Icon(Icons.Default.OndemandVideo, null) }, onClick = { showPlayerMenu = false; onYoutubeVideoToggle(!state.youtubeVideoEnabled) })
                    }
                    if (state.current?.mediaId?.startsWith("stream:https://www.youtube.com/") == true || state.current?.mediaId?.startsWith("stream:https://music.youtube.com/") == true) {
                        DropdownMenuItem(text = { Text("Playlist YouTube") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { showPlayerMenu = false; newPlaylistName = ""; showCreatePlaylist = true; onLoadYtPlaylists() })
                    }
                }
            }
        }
        if (showDownloadChoices) AlertDialog(
            onDismissRequest = { showDownloadChoices = false }, title = { Text("Destination du téléchargement") },
            text = { Column {
                TextButton(onClick = { showDownloadChoices = false; state.current?.let { onDownloadTrack(it, "local") } }) { Text("Local · Music/Downloads") }
                state.remoteProfiles.forEach { profile -> TextButton(onClick = { showDownloadChoices = false; state.current?.let { onDownloadTrack(it, "${profile.type}:${profile.id}") } }) { Text("${profile.type.uppercase()} · ${profile.name}") } }
                TextButton(onClick = { showDownloadChoices = false; state.current?.let { onDownloadTrack(it, "pcloud") } }) { Text("pCloud") }
            } }, confirmButton = { TextButton(onClick = { showDownloadChoices = false }) { Text("Annuler") } })
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.14f)).pointerInput(Unit) { var drag = 0f; detectVerticalDragGestures(onVerticalDrag = { change, amount -> change.consume(); drag += amount }, onDragEnd = { if (drag > 45f) onClose(); drag = 0f }, onDragCancel = { drag = 0f }) }, contentAlignment = Alignment.Center) {
            val currentPage = state.current?.mediaId?.removePrefix("stream:")
            if (state.youtubeVideoEnabled && currentPage != null && (currentPage.contains("youtube.com/", true) || currentPage.contains("youtu.be/", true))) {
                YoutubeVideoArtwork(currentPage, state.position, state.isPlaying, state.videoAspect)
            } else {
                ArtworkImage(YouTubeArtwork.resolve(state.current?.mediaId, state.current?.localConfiguration?.uri?.toString(), md.artworkUri), Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), fallbackSize = 96.dp, showArtwork = state.showArtwork, fallbackUri = state.current?.localConfiguration?.uri)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(md.title?.toString() ?: "", color = Color.White, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val isYoutubeTrack = state.current?.mediaId?.startsWith("stream:") == true &&
            (state.current?.mediaId?.contains("youtube.com/", ignoreCase = true) == true || state.current?.mediaId?.contains("youtu.be/", ignoreCase = true) == true)
        val displayedArtist = if (isYoutubeTrack) cleanArtist(md.artist?.toString().orEmpty()) else md.artist?.toString().orEmpty()
        Text(
            displayedArtist,
            color = if (hasCreatorProfile || (isYoutubeTrack && displayedArtist.isNotBlank() && !displayedArtist.equals("YouTube", true))) accent else Muted,
            modifier = if (hasCreatorProfile) Modifier.clickable { onOpenArtist(state.current!!) }
                else if (isYoutubeTrack && displayedArtist.isNotBlank() && !displayedArtist.equals("YouTube", true)) Modifier.clickable {
                    onSearchArtist(displayedArtist)
                } else Modifier,
        )
        Spacer(Modifier.height(12.dp))
        Slider(value = state.position.toFloat().coerceAtMost(state.duration.toFloat().coerceAtLeast(1f)), onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..state.duration.toFloat().coerceAtLeast(1f), colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Card))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(state.position), color = Muted, fontSize = 12.sp)
            Text(fmt(state.duration), color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val scope = rememberCoroutineScope()
            // Appui court : titre précédent. Appui maintenu : retour rapide de 10 s toutes les 0,5 s.
            Box(
                Modifier.size(48.dp).clip(CircleShape).pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        var rewind = false
                        val job = scope.launch {
                            delay(400)
                            rewind = true
                            while (true) { onSeekBy(-10_000L); delay(500) }
                        }
                        val released = tryAwaitRelease()
                        job.cancel()
                        if (released && !rewind) onPrev()
                    })
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.SkipPrevious, "Précédent (maintenir pour reculer de 10 s)", tint = Color.White, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = onToggle, modifier = Modifier.size(64.dp).clip(CircleShape).background(accent)) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Lecture / pause", tint = Color.Black, modifier = Modifier.size(36.dp)) }
            // Appui court : titre suivant. Appui maintenu : avance rapide de 10 s toutes les 0,5 s.
            Box(
                Modifier.size(48.dp).clip(CircleShape).pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        var fastForward = false
                        val job = scope.launch {
                            delay(400)
                            fastForward = true
                            while (true) { onSeekBy(10_000L); delay(500) }
                        }
                        val released = tryAwaitRelease()
                        job.cancel()
                        if (released && !fastForward) onNext()
                    })
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.SkipNext, "Suivant (maintenir pour avancer de 10 s)", tint = Color.White, modifier = Modifier.size(36.dp)) }
        }
        Spacer(Modifier.weight(1f))
    }
    Box(
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().height(48.dp)
            .zIndex(1f)
            .then(if (state.queueSwipeEnabled) Modifier.pointerInput(state.queueSwipeEnabled) {
                var drag = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount -> change.consume(); drag += amount },
                    onDragEnd = { if (drag < -45f) { showQueue = true; queueRequested = true; onOpenQueue() }; drag = 0f },
                    onDragCancel = { drag = 0f },
                )
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(48.dp).then(if (!state.queueSwipeEnabled) Modifier.clickable {
                showQueue = true
                queueRequested = true
                onOpenQueue()
            } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = if (state.queueSwipeEnabled) "Balayer vers le haut pour ouvrir les morceaux suivants" else "Ouvrir la liste des morceaux suivants",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                modifier = Modifier.size(32.dp),
            )
        }
    }
    }
    LaunchedEffect(state.queueTracks, queueRequested) { if (queueRequested) queueRequested = false }
    if (showQueue) ModalBottomSheet(onDismissRequest = { showQueue = false }) {
        Text("Morceaux suivants", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            itemsIndexed(state.queueTracks) { index, track ->
                val title = track.mediaMetadata.title?.toString().orEmpty()
                val artist = track.mediaMetadata.artist?.toString().orEmpty()
                Row(Modifier.fillMaxWidth().clickable { showQueue = false; onPlayQueueTrack(index) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = if (track.mediaId == state.current?.mediaId) accent else Muted)
                    Spacer(Modifier.width(12.dp))
                    Column { Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(artist, color = Muted, fontSize = 12.sp, maxLines = 1) }
                }
            }
        }
    }
    if (showCreatePlaylist) AlertDialog(
        onDismissRequest = { showCreatePlaylist = false }, title = { Text("Créer une playlist YouTube") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text("Nom") }, singleLine = true)
            Text("Playlists existantes", color = Muted, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            if (state.ytPlaylists.isEmpty()) Text("Aucune playlist chargée", color = Muted, fontSize = 12.sp)
            state.ytPlaylists.forEach { playlist -> TextButton(onClick = { showCreatePlaylist = false; onAddYtPlaylist(playlist.id) }, modifier = Modifier.fillMaxWidth()) { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        } },
        confirmButton = { TextButton(enabled = newPlaylistName.isNotBlank(), onClick = { showCreatePlaylist = false; onCreateYtPlaylist(newPlaylistName) }) { Text("Créer et ajouter") } },
        dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Annuler") } })
    if (showAudioTuning) AlertDialog(
        onDismissRequest = { showAudioTuning = false },
        title = { Text("Vitesse et pitch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Vitesse", color = Color.White)
                    Text("${"%.2f".format(java.util.Locale.FRANCE, speed)}×", color = accent)
                }
                Slider(value = speed, onValueChange = { speed = (kotlin.math.round(it * 100f) / 100f).coerceIn(0.5f, 2f) }, valueRange = 0.5f..2f, steps = 149, colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pitch", color = Color.White)
                    Text("${"%.2f".format(java.util.Locale.FRANCE, pitch)}×", color = accent)
                }
                Slider(value = pitch, onValueChange = { pitch = (kotlin.math.round(it * 100f) / 100f).coerceIn(0.5f, 2f) }, valueRange = 0.5f..2f, steps = 149, colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberParameters, onCheckedChange = { rememberParameters = it })
                    Text("Mémoriser", color = Color.White)
                }
                Text("La vitesse et la hauteur du son sont réglables séparément.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onPlaybackParametersChange(speed, pitch, rememberParameters); showAudioTuning = false }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = { speed = 1f; pitch = 1f; onPlaybackParametersChange(1f, 1f, rememberParameters); showAudioTuning = false }) { Text("Réinitialiser") } },
    )
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun YoutubeVideoArtwork(pageUrl: String, positionMs: Long, isPlaying: Boolean, aspect: String) {
    val context = LocalContext.current
    val player = remember { androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply { volume = 0f } }
    var resolvedUrl by remember(pageUrl) { mutableStateOf<String?>(null) }
    var videoError by remember(pageUrl) { mutableStateOf(false) }
    LaunchedEffect(pageUrl) {
        resolvedUrl = withContext(Dispatchers.IO) { runCatching { StreamSources.resolveVideo360Url(pageUrl) }.getOrNull() }
        if (resolvedUrl == null) videoError = true
        else {
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(resolvedUrl!!))
            player.prepare()
        }
    }
    LaunchedEffect(aspect) { /* resize mode applied by AndroidView update below */ }
    LaunchedEffect(pageUrl, positionMs, isPlaying, resolvedUrl) {
        if (resolvedUrl != null) {
            val delta = kotlin.math.abs(player.currentPosition - positionMs)
            if (delta > 900L) player.seekTo(positionMs.coerceAtLeast(0L))
            if (isPlaying) player.play() else player.pause()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    if (videoError) Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("Vidéo YouTube indisponible", color = Color.White, fontSize = 13.sp)
    } else AndroidView(factory = { viewContext ->
        androidx.media3.ui.PlayerView(viewContext).apply {
            useController = false
            resizeMode = if (aspect == "fill") androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            if (aspect == "fill") { scaleX = 1.12f; scaleY = 1.12f } else { scaleX = 1f; scaleY = 1f }
            this.player = player
            (findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame))?.apply { setAspectRatio(0f); clipToOutline = true }
        }
    }, update = { view ->
        view.player = player
        view.resizeMode = if (aspect == "fill") androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        if (aspect == "fill") { view.scaleX = 1.12f; view.scaleY = 1.12f } else { view.scaleX = 1f; view.scaleY = 1f }
        view.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)?.apply { setAspectRatio(0f); clipToOutline = true }
    }, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
}

@Composable
private fun ArtworkImage(uri: Uri?, modifier: Modifier = Modifier, fallbackSize: androidx.compose.ui.unit.Dp = 24.dp, showArtwork: Boolean = true, fallbackUri: Uri? = null) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, showArtwork, fallbackUri) {
        value = null
        if (showArtwork && (uri != null || fallbackUri != null)) {
            value = withContext(Dispatchers.IO) {
                val artworkBitmap = uri?.let { artworkUri -> runCatching {
                    val stream = when (artworkUri.scheme?.lowercase()) {
                        "content", "android.resource", "file" -> context.contentResolver.openInputStream(artworkUri)
                        "http", "https" -> {
                            val connection = (URL(artworkUri.toString()).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 6000
                                readTimeout = 6000
                                instanceFollowRedirects = true
                            }
                            connection.connect()
                            connection.inputStream
                        }
                        else -> null
                    }
                    stream?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) null else {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            var sample = 1
                            while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
                            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        }
                    }
                }.getOrNull() }
                artworkBitmap ?: fallbackUri?.takeIf { it.scheme == "content" || it.scheme == "file" }?.let { audioUri -> runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, audioUri)
                        retriever.embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    } finally { retriever.release() }
                }.getOrNull() }
            }
        }
    }
    if (showArtwork && bitmap != null) {
        Image(bitmap!!.asImageBitmap(), contentDescription = "Pochette", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Art), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = Color(0xFFCECBF6), modifier = Modifier.size(fallbackSize))
        }
    }
}

private fun isAudioFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf(
    "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "aiff", "aif", "ape", "alac", "amr", "mid", "midi", "mp2", "wv"
)

private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

private fun accentFor(key: String): Color = when (key) {
    "Bleu" -> Color(0xFF448AFF)
    "Violet" -> Color(0xFF9C6BFF)
    "Rouge" -> Color(0xFFFF5252)
    "Orange" -> Color(0xFFFF9800)
    "Rose" -> Color(0xFFFF4081)
    else -> Color(0xFF1DB954)
}

@Composable
private fun ArtistProfileOverlay(
    state: PlayerUiState,
    accent: Color,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    BackHandler(enabled = true, onBack = onClose)
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onClose() }) { Text("‹ Lecteur") }
            Text(state.artistProfileTitle ?: "Profil artiste", color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            state.streamLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state.streamError != null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.streamError, color = Color(0xFFFF8A80))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onRetry() }) { Text("Réessayer") }
            }
            state.streamResults.isEmpty() -> Text("Aucun titre trouvé pour cet artiste.", color = Muted, modifier = Modifier.padding(24.dp))
            else -> LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(state.streamResults) { index, item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlay(index) }.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkImage(YouTubeArtwork.resolve(item.mediaId, item.localConfiguration?.uri?.toString(), item.mediaMetadata.artworkUri), Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)), showArtwork = state.showArtwork, fallbackUri = item.localConfiguration?.uri)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.mediaMetadata.title?.toString() ?: "Sans titre", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.mediaMetadata.artist?.toString() ?: "", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.PlayArrow, "Lire", tint = accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamPane(
    source: String,
    state: PlayerUiState,
    accent: Color,
    onPlay: (Int) -> Unit,
    yt: AccountActions,
    sc: AccountActions,
    modifier: Modifier,
    onOpenArtist: (MediaItem) -> Unit,
    onOpenLocalPlaylist: (String) -> Unit,
    trackListState: LazyListState,
    onCloseArtistProfile: () -> Unit,
) {
    val isYt = source == "YouTube"
    val actions = if (isYt) yt else sc
    val loggedIn = if (isYt) state.ytLoggedIn else state.scLoggedIn
    val playlists = if (isYt) state.ytPlaylists else state.scPlaylists
    val playlistTitle = if (isYt) state.ytPlaylistTitle else state.scPlaylistTitle
    val view = if (loggedIn) (if (isYt) state.ytView else state.scView) else "Recherche"
    BackHandler(enabled = state.artistProfileTitle != null || (loggedIn && playlistTitle != null) || state.localPlaylistTitle != null) {
        if (state.artistProfileTitle != null) onCloseArtistProfile() else actions.show(view)
    }
    val active = state.streamSource == source
    val results = if (active) state.streamResults else emptyList()
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = Card, labelColor = Color.White,
        selectedContainerColor = accent, selectedLabelColor = Color.Black,
    )

    Column(modifier) {
        if (state.localPlaylists.isNotEmpty() && state.artistProfileTitle == null) {
            Text("Playlists locales", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.localPlaylists) { name -> AssistChip(onClick = { onOpenLocalPlaylist(name) }, label = { Text(name) }) }
            }
        }
        state.artistProfileTitle?.let { title ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onCloseArtistProfile() }) { Text("‹ Lecteur") }
                Text(title, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (loggedIn && state.artistProfileTitle == null && state.localPlaylistTitle == null) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items((if (isYt)
                    listOf("Accueil", "Playlists", "Uploads", "Historique", "Aimés")
                else listOf("Accueil", "Uploads", "Playlists", "Historique", "Aimés")).filter { it !in state.disabledSubTabs }) { v ->
                    FilterChip(selected = view == v, onClick = { actions.show(v) }, label = { Text(v) }, colors = chipColors)
                }
            }
        } else if (state.artistProfileTitle == null && state.localPlaylistTitle == null) {
            Text(
                "Connecte-toi à $source dans les paramètres (roue dentée) pour retrouver tes playlists et ton historique.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (active && state.streamLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (active) state.streamError?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(16.dp), maxLines = 10, overflow = TextOverflow.Ellipsis) }

        if (view == "Playlists" && playlistTitle == null && state.artistProfileTitle == null && state.localPlaylistTitle == null) {
            if (state.localPlaylists.isNotEmpty()) {
                Text("Playlists locales", color = Muted, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                state.localPlaylists.forEach { name -> TextButton(onClick = { onOpenLocalPlaylist(name) }) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text(name) } }
            }
            if (!state.streamLoading && playlists.isEmpty() && state.streamError == null) {
                Text("Aucune playlist.", color = Muted, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(playlists) { pl ->
                    Row(
                        Modifier.fillMaxWidth().clickable { actions.openPlaylist(pl.id, pl.title) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Folder, null, tint = accent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pl.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (pl.subtitle.isNotBlank()) Text(pl.subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else {
            if (((view == "Playlists" || view == "Accueil") && playlistTitle != null) || state.localPlaylistTitle != null) {
                TextButton(onClick = { actions.show(view) }) { Text("‹ $view · ${state.localPlaylistTitle ?: playlistTitle}") }
            }
            if (state.artistProfileTitle == null && state.localPlaylistTitle == null && view == "Recherche" && !active) {
                Text("Utilise la loupe en haut à droite pour rechercher sur $source.", color = Muted, modifier = Modifier.padding(16.dp))
            } else if ((active || state.artistProfileTitle != null || state.localPlaylistTitle != null) && !state.streamLoading && results.isEmpty()) {
                Text(if (state.streamError == null) "Aucun résultat." else state.streamError ?: "Résultat vide — aucun contenu exploitable reçu.", color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            LaunchedEffect(state.isNowPlayingExpanded, state.current?.mediaId, source, results.map { it.mediaId }) {
                if (!state.isNowPlayingExpanded) {
                    val currentIndex = results.indexOfFirst { it.mediaId == state.current?.mediaId }
                    if (currentIndex >= 0) trackListState.animateScrollToItem(currentIndex)
                }
            }
            LazyColumn(state = trackListState, modifier = Modifier.weight(1f)) {
                itemsIndexed(results) { i, item ->
                    val md = item.mediaMetadata
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlay(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkImage(YouTubeArtwork.resolve(item.mediaId, item.localConfiguration?.uri?.toString(), md.artworkUri), Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)), showArtwork = state.showArtwork)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(md.title?.toString() ?: "Sans titre", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                md.artist?.toString() ?: source,
                                color = Muted,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier,
                            )
                        }
                        Icon(Icons.Default.PlayArrow, "Lire", tint = accent)
                    }
                }
            }
        }

    }
}

/** Vraie page de connexion dans une WebView ; on récupère la session dès qu'elle existe (ou via le bouton Terminé). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AccountLoginScreen(
    title: String,
    startUrl: String,
    pageHost: String,
    hasSession: (String) -> Boolean,
    onCookie: (String) -> Unit,
    onClose: () -> Unit,
    captureApiToken: Boolean = false,
) {
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    // Les sites de connexion modernes ne rechargent pas toujours la page : on surveille aussi les cookies.
    LaunchedEffect(pageHost) {
        while (true) {
            delay(1000)
            val cookie = CookieManager.getInstance().getCookie(pageHost)
            if (cookie != null && hasSession(cookie)) { onCookie(cookie); break }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onClose() }) { Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White) }
            Text(title, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val cookie = CookieManager.getInstance().getCookie(pageHost)
                if (cookie != null && hasSession(cookie)) {
                    onCookie(cookie)
                } else {
                    // Recharger la page force le site à refaire ses appels, d'où on peut relever la session.
                    web?.reload()
                    Toast.makeText(context, "Session pas encore trouvée. Connecte-toi, attends la fin du chargement, puis appuie de nouveau sur Terminé.", Toast.LENGTH_LONG).show()
                }
            }) { Text("Terminé") }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    web = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // User-Agent de navigateur classique : certains sites refusent la connexion dans une WebView "brute".
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null && url.startsWith(pageHost)) {
                                val cookie = CookieManager.getInstance().getCookie(pageHost)
                                if (cookie != null && hasSession(cookie)) onCookie(cookie)
                            }
                        }

                        // SoundCloud : une fois connecté, le site envoie son jeton dans l'en-tête Authorization de ses appels API.
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            if (captureApiToken && request != null && request.url.host?.endsWith("soundcloud.com") == true) {
                                val auth = request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
                                if (auth != null && auth.startsWith("OAuth ")) {
                                    val token = auth.removePrefix("OAuth ").trim()
                                    if (token.isNotBlank()) Handler(Looper.getMainLooper()).post { onCookie("oauth_token=$token") }
                                }
                            }
                            return null
                        }
                    }
                    loadUrl(startUrl)
                }
            },
        )
    }
}


private fun fetchSearchSuggestions(source: String, query: String): List<String> = runCatching {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = if (source == StreamSources.YOUTUBE)
        "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$encoded"
    else "https://api-v2.soundcloud.com/search/queries?q=$encoded&client_id=${SoundCloudClient.currentClientIdForSuggestions()}"
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000; readTimeout = 5000; setRequestProperty("User-Agent", "Mozilla/5.0")
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching emptyList()
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        if (source == StreamSources.YOUTUBE) {
            val root = JSONArray(text).optJSONArray(1) ?: return@runCatching emptyList()
            (0 until root.length()).mapNotNull { root.optString(it).takeIf(String::isNotBlank) }.distinct().take(8)
        } else {
            val root = JSONObject(text).optJSONArray("collection") ?: return@runCatching emptyList()
            (0 until root.length()).mapNotNull { root.optJSONObject(it)?.optString("query")?.takeIf(String::isNotBlank) }.distinct().take(8)
        }
    } finally { connection.disconnect() }
}.getOrDefault(emptyList())
