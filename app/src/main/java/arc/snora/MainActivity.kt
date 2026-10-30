package arc.snora

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import arc.snora.data.MediaRecord
import arc.snora.data.MediaRepository
import arc.snora.home.HomeScreen
import arc.snora.library.FavoritesScreen
import arc.snora.library.FoldersScreen
import arc.snora.library.HistoryScreen
import arc.snora.library.VideosScreen
import arc.snora.models.FolderGroup
import arc.snora.models.VideoItem
import arc.snora.navigation.AppDestination
import arc.snora.player.HwDecMode
import arc.snora.player.PlayerController
import arc.snora.player.PlayerView
import arc.snora.player.SnoraPlayer
import arc.snora.settings.SettingsScreen
import arc.snora.ui.theme.SnoraTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var player: SnoraPlayer
    private lateinit var playerController: PlayerController
    private lateinit var repository: MediaRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = MediaRepository(this)
        player = SnoraPlayer(this)
        playerController = PlayerController(this, player)

        setContent {
            SnoraTheme {
                MainContent(
                    player = player,
                    controller = playerController,
                    repository = repository,
                    initialIntent = intent
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        player.destroy()
    }
}

@Composable
fun MainContent(
    player: SnoraPlayer,
    controller: PlayerController,
    repository: MediaRepository,
    initialIntent: Intent?
) {
    val scope = rememberCoroutineScope()
    var currentDestination by remember { mutableStateOf(AppDestination.VIDEOS) }
    var activeVideo by remember { mutableStateOf<VideoItem?>(null) }
    var activeTitle by remember { mutableStateOf("") }

    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var folders by remember { mutableStateOf<List<FolderGroup>>(emptyList()) }
    var isLoadingVideos by remember { mutableStateOf(false) }

    val historyRecords by repository.historyFlow.collectAsState(initial = emptyList())
    val favoriteRecords by repository.favoritesFlow.collectAsState(initial = emptyList())
    val favoriteUris = remember(favoriteRecords) { favoriteRecords.map { it.uriString }.toSet() }

    var currentHwDec by remember { mutableStateOf(HwDecMode.AUTO) }

    fun refreshMedia() {
        scope.launch {
            isLoadingVideos = true
            videos = repository.queryDeviceVideos()
            folders = repository.queryFolderGroups()
            isLoadingVideos = false
        }
    }

    fun playItem(uriString: String, title: String, path: String) {
        scope.launch {
            val resumePosition = repository.getResumePosition(uriString)
            activeVideo = VideoItem(
                id = uriString.hashCode().toLong(),
                title = title,
                uriString = uriString,
                path = path,
                durationMs = 0L,
                sizeBytes = 0L,
                dateModified = System.currentTimeMillis(),
                resolution = "",
                folderName = ""
            )
            activeTitle = title
            player.playMedia(uriString, resumePosition)
        }
    }

    fun closePlayer() {
        val currentVideo = activeVideo
        if (currentVideo != null) {
            val position = player.playbackState.value.positionMs
            val duration = player.playbackState.value.durationMs
            scope.launch {
                repository.savePlaybackPosition(
                    uriString = currentVideo.uriString,
                    title = currentVideo.title,
                    path = currentVideo.path,
                    positionMs = position,
                    durationMs = duration
                )
            }
        }
        player.pause()
        activeVideo = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshMedia()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val uriStr = it.toString()
            val fileName = it.lastPathSegment?.substringAfterLast('/') ?: "External Media"
            playItem(uriStr, fileName, "")
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            )
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
        refreshMedia()
    }

    LaunchedEffect(initialIntent) {
        val dataUri = initialIntent?.data
        if (dataUri != null) {
            val title = dataUri.lastPathSegment?.substringAfterLast('/') ?: "External Media"
            playItem(dataUri.toString(), title, "")
        }
    }

    if (activeVideo != null) {
        BackHandler {
            closePlayer()
        }

        PlayerView(
            controller = controller,
            title = activeTitle,
            onBack = { closePlayer() }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar(modifier = Modifier.testTag("snora_bottom_navigation")) {
                    AppDestination.values().forEach { destination ->
                        val isSelected = currentDestination == destination
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { currentDestination = destination },
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.title
                                )
                            },
                            label = { Text(destination.title) },
                            modifier = Modifier.testTag("nav_${destination.name.lowercase()}")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentDestination) {
                    AppDestination.VIDEOS -> {
                        VideosScreen(
                            videos = videos,
                            favorites = favoriteUris,
                            isLoading = isLoadingVideos,
                            onRefresh = { refreshMedia() },
                            onPlayVideo = { item -> playItem(item.uriString, item.title, item.path) },
                            onToggleFavorite = { item ->
                                scope.launch {
                                    repository.toggleFavorite(item.uriString, item.title, item.path, item.durationMs)
                                }
                            }
                        )
                    }
                    AppDestination.FOLDERS -> {
                        FoldersScreen(
                            folders = folders,
                            favorites = favoriteUris,
                            onPlayVideo = { item -> playItem(item.uriString, item.title, item.path) },
                            onToggleFavorite = { item ->
                                scope.launch {
                                    repository.toggleFavorite(item.uriString, item.title, item.path, item.durationMs)
                                }
                            }
                        )
                    }
                    AppDestination.HOME -> {
                        val latestHistory = historyRecords.firstOrNull()
                        HomeScreen(
                            recentRecord = latestHistory,
                            recentVideos = videos,
                            onOpenFilePicker = { filePickerLauncher.launch("video/*") },
                            onOpenUrl = { url -> playItem(url, url.substringAfterLast('/'), "") },
                            onPlayRecord = { record -> playItem(record.uriString, record.title, record.path) },
                            onPlayVideo = { item -> playItem(item.uriString, item.title, item.path) }
                        )
                    }
                    AppDestination.HISTORY -> {
                        HistoryScreen(
                            history = historyRecords,
                            onPlayRecord = { record -> playItem(record.uriString, record.title, record.path) },
                            onDeleteRecord = { record ->
                                scope.launch {
                                    repository.savePlaybackPosition(record.uriString, record.title, record.path, 0L, 0L)
                                }
                            }
                        )
                    }
                    AppDestination.FAVORITES -> {
                        FavoritesScreen(
                            favorites = favoriteRecords,
                            onPlayRecord = { record -> playItem(record.uriString, record.title, record.path) },
                            onRemoveFavorite = { record ->
                                scope.launch {
                                    repository.toggleFavorite(record.uriString, record.title, record.path, record.durationMs)
                                }
                            }
                        )
                    }
                    AppDestination.SETTINGS -> {
                        SettingsScreen(
                            currentHwDec = currentHwDec,
                            onHwDecChange = { mode ->
                                currentHwDec = mode
                                player.videoRenderer.setHwDecMode(mode)
                            }
                        )
                    }
                }
            }
        }
    }
}
