package arc.snora

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import arc.snora.data.MediaRepository
import arc.snora.home.HomeScreen
import arc.snora.library.VideosScreen
import arc.snora.models.FolderGroup
import arc.snora.models.VideoItem
import arc.snora.navigation.AppDestination
import arc.snora.player.HwDecMode
import arc.snora.player.PlayerController
import arc.snora.player.PlayerView
import arc.snora.player.SnoraPlayer
import arc.snora.player.SubtitleAppearance
import arc.snora.settings.SettingsScreen
import arc.snora.settings.SubtitleSettingsScreen
import arc.snora.settings.SubtitleSettingsStore
import arc.snora.ui.theme.SnoraTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var player: SnoraPlayer
    private lateinit var playerController: PlayerController
    private lateinit var repository: MediaRepository
    private var isPlayerActive: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        repository = MediaRepository(this)
        player = SnoraPlayer(this)
        playerController = PlayerController(this, player)

        setContent {
            SnoraTheme {
                MainContent(
                    activity = this,
                    player = player,
                    controller = playerController,
                    repository = repository,
                    initialIntent = intent
                )
            }
        }
    }

    fun setPlayerActive(active: Boolean) {
        isPlayerActive = active
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isPlayerActive && ::playerController.isInitialized) {
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> 1
                KeyEvent.KEYCODE_VOLUME_DOWN -> -1
                else -> 0
            }
            if (direction != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    playerController.onHardwareVolumeKey(direction)
                }
                // Consume both key down and key up so Android's volume panel is
                // replaced by SNORA's own gesture indicator while playing.
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (isPlayerActive && ::playerController.isInitialized) {
            playerController.syncWithDevice()
        }
    }

    override fun onDestroy() {
        if (::playerController.isInitialized) {
            playerController.onPlayerViewDisposed(this)
        }
        super.onDestroy()
        if (::player.isInitialized) {
            player.destroy()
        }
    }
}

@Composable
fun MainContent(
    activity: MainActivity,
    player: SnoraPlayer,
    controller: PlayerController,
    repository: MediaRepository,
    initialIntent: Intent?
) {
    val scope = rememberCoroutineScope()
    var currentDestination by remember { mutableStateOf(AppDestination.HOME) }
    var activeVideo by remember { mutableStateOf<VideoItem?>(null) }
    var activeTitle by remember { mutableStateOf("") }
    var showSubtitleSettings by remember { mutableStateOf(false) }
    val subtitleSettingsStore = remember(activity) { SubtitleSettingsStore(activity) }
    var subtitleAppearance by remember {
        mutableStateOf<SubtitleAppearance>(subtitleSettingsStore.load())
    }

    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var folders by remember { mutableStateOf<List<FolderGroup>>(emptyList()) }
    var isLoadingVideos by remember { mutableStateOf(false) }

    val playbackState by player.playbackState.collectAsState()
    var currentHwDec by remember { mutableStateOf(HwDecMode.AUTO) }

    val insetsController = remember(activity) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    }

    fun setFullscreen(fullscreen: Boolean) {
        if (fullscreen) {
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

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
            activity.setPlayerActive(true)
            setFullscreen(true)
            val source = if (path.isNotBlank()) path else uriString
            val customAppearance = subtitleAppearance.takeUnless { it.isDefault() }
            if (customAppearance == null) {
                // Let the player use its own defaults when nothing was customized.
                player.playMedia(source, 0L, title = title)
            } else {
                player.playMedia(source, 0L, customAppearance, title)
            }
        }
    }

    fun closePlayer() {
        activity.setPlayerActive(false)
        player.stopPlayback()
        setFullscreen(false)
        activeVideo = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshMedia()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
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
        BackHandler(enabled = true) {
            if (!playbackState.isControlsLocked) {
                closePlayer()
            }
        }

        PlayerView(
            controller = controller,
            title = activeTitle,
            onBack = {
                if (!playbackState.isControlsLocked) {
                    closePlayer()
                }
            }
        )
    } else if (showSubtitleSettings) {
        BackHandler(enabled = true) {
            showSubtitleSettings = false
        }
        SubtitleSettingsScreen(
            appearance = subtitleAppearance,
            onAppearanceChange = { appearance ->
                subtitleAppearance = appearance
                subtitleSettingsStore.save(appearance)
            },
            onBack = { showSubtitleSettings = false }
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
                    AppDestination.HOME -> {
                        HomeScreen(
                            folders = folders,
                            onPlayVideo = { item -> playItem(item.uriString, item.title, item.path) },
                            onOpenUrl = { url -> playItem(url, url.substringAfterLast('/'), "") }
                        )
                    }
                    AppDestination.VIDEOS -> {
                        VideosScreen(
                            videos = videos,
                            isLoading = isLoadingVideos,
                            onRefresh = { refreshMedia() },
                            onPlayVideo = { item -> playItem(item.uriString, item.title, item.path) }
                        )
                    }
                    AppDestination.SETTINGS -> {
                        SettingsScreen(
                            currentHwDec = currentHwDec,
                            onHwDecChange = { mode ->
                                currentHwDec = mode
                                player.videoRenderer.setHwDecMode(mode)
                            },
                            onSubtitleSettingsClick = { showSubtitleSettings = true }
                        )
                    }
                }
            }
        }
    }
}
