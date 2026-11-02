package arc.snora.player

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Icon
import android.media.session.MediaSession
import android.os.Build
import android.widget.RemoteViews
import java.util.Locale

/** Lightweight media notification backed by the existing player and MediaSession. */
internal class PlayerNotificationManager(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun show(
        state: PlaybackState,
        title: String,
        poster: Bitmap?,
        mediaSession: MediaSession?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()

        val isPlaying = state.status == PlaybackStatus.PLAYING
        val actionIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val actionTitle = if (isPlaying) "Pause" else "Play"
        val playPauseIntent = PlaybackActionReceiver.playPauseIntent(context)
        val timeText = "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}"
        val style = Notification.MediaStyle()
            .setShowActionsInCompactView(0)
        mediaSession?.let { style.setMediaSession(it.token) }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title)
            .setContentText(timeText)
            .setContentIntent(createContentIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setStyle(style)
            .setCustomContentView(buildRemoteViews(state, title, poster, isPlaying))
            .setCustomBigContentView(buildRemoteViews(state, title, poster, isPlaying))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, actionIcon),
                    actionTitle,
                    playPauseIntent
                ).build()
            )
        poster?.let { builder.setLargeIcon(it) }

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (t: Throwable) {
            // Some OEMs reject custom media layouts; playback must remain unaffected.
        }
    }

    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun buildRemoteViews(
        state: PlaybackState,
        title: String,
        poster: Bitmap?,
        isPlaying: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_player)
        if (poster != null) {
            views.setImageViewBitmap(R.id.notification_poster, poster)
        } else {
            views.setImageViewResource(R.id.notification_poster, R.drawable.ic_replay)
        }
        views.setTextViewText(R.id.notification_title, title)
        views.setTextViewText(R.id.notification_played_time, formatTime(state.positionMs))
        views.setTextViewText(R.id.notification_duration, formatTime(state.durationMs))
        views.setImageViewResource(
            R.id.notification_play_pause,
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setContentDescription(
            R.id.notification_play_pause,
            if (isPlaying) "Pause video" else "Play video"
        )
        views.setOnClickPendingIntent(
            R.id.notification_play_pause,
            PlaybackActionReceiver.playPauseIntent(context)
        )

        views.removeAllViews(R.id.notification_progress_segments)
        val chapters = state.chapters
        if (chapters.isEmpty()) {
            views.addView(
                R.id.notification_progress_segments,
                createProgressSegment(
                    progress = fractionToPermille(state.positionMs, 0L, state.durationMs),
                    showDivider = false
                )
            )
        } else {
            chapters.forEachIndexed { index, chapter ->
                val segment = createProgressSegment(
                    progress = fractionToPermille(
                        positionMs = state.positionMs,
                        startMs = chapter.startMs,
                        endMs = chapter.endMs
                    ),
                    showDivider = index < chapters.lastIndex
                )
                segment.setOnClickPendingIntent(
                    R.id.notification_segment_root,
                    PlaybackActionReceiver.seekIntent(
                        context = context,
                        positionMs = chapter.startMs.coerceAtLeast(0L),
                        requestCode = 7_200 + index
                    )
                )
                segment.setContentDescription(
                    R.id.notification_segment_root,
                    "Go to ${chapter.title}"
                )
                views.addView(R.id.notification_progress_segments, segment)
            }
        }
        return views
    }

    private fun createProgressSegment(progress: Int, showDivider: Boolean): RemoteViews {
        val segment = RemoteViews(context.packageName, R.layout.notification_progress_segment)
        segment.setProgressBar(
            R.id.notification_segment_progress,
            1_000,
            progress.coerceIn(0, 1_000),
            false
        )
        if (!showDivider) {
            viewsSetGone(segment, R.id.notification_segment_divider)
        }
        return segment
    }

    private fun viewsSetGone(views: RemoteViews, viewId: Int) {
        views.setViewVisibility(viewId, android.view.View.GONE)
    }

    private fun fractionToPermille(positionMs: Long, startMs: Long, endMs: Long): Int {
        if (endMs <= startMs) return 0
        return when {
            positionMs >= endMs -> 1_000
            positionMs <= startMs -> 0
            else -> (((positionMs - startMs).toDouble() / (endMs - startMs).toDouble()) * 1_000.0)
                .toInt()
                .coerceIn(0, 1_000)
        }
    }

    private fun createContentIntent(): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            7_100,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Video playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Current video, progress and playback controls"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val CHANNEL_ID = "snora_video_playback"
        private const val NOTIFICATION_ID = 7_001
    }
}
