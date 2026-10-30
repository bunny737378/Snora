package arc.snora.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    VIDEOS("Videos", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircleOutline),
    FOLDERS("Folders", Icons.Filled.Folder, Icons.Outlined.Folder),
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    HISTORY("History", Icons.Filled.History, Icons.Outlined.History),
    FAVORITES("Favorites", Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}
