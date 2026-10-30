package arc.snora.models

data class VideoItem(
    val id: Long,
    val title: String,
    val uriString: String,
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModified: Long,
    val resolution: String,
    val folderName: String
)

data class FolderGroup(
    val name: String,
    val path: String,
    val videoCount: Int,
    val videos: List<VideoItem>
)
