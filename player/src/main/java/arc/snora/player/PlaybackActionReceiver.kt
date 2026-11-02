package arc.snora.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes notification actions to the existing player instead of creating another engine. */
class PlaybackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val player = activePlayer ?: return
        when (intent.action) {
            ACTION_PLAY_PAUSE -> player.togglePlayPause()
            ACTION_SEEK_TO -> {
                if (intent.hasExtra(EXTRA_POSITION_MS)) {
                    player.seekTo(intent.getLongExtra(EXTRA_POSITION_MS, 0L))
                }
            }
        }
    }

    companion object {
        private const val ACTION_PLAY_PAUSE = "arc.snora.player.action.PLAY_PAUSE"
        private const val ACTION_SEEK_TO = "arc.snora.player.action.SEEK_TO"
        private const val EXTRA_POSITION_MS = "position_ms"

        @Volatile
        private var activePlayer: SnoraPlayer? = null

        internal fun attach(player: SnoraPlayer) {
            activePlayer = player
        }

        internal fun detach(player: SnoraPlayer) {
            if (activePlayer === player) activePlayer = null
        }

        internal fun playPauseIntent(context: Context): PendingIntent {
            return PendingIntent.getBroadcast(
                context,
                7_101,
                Intent(context, PlaybackActionReceiver::class.java).setAction(ACTION_PLAY_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        internal fun seekIntent(
            context: Context,
            positionMs: Long,
            requestCode: Int
        ): PendingIntent {
            val intent = Intent(context, PlaybackActionReceiver::class.java)
                .setAction(ACTION_SEEK_TO)
                .putExtra(EXTRA_POSITION_MS, positionMs)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
