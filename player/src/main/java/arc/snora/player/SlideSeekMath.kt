package arc.snora.player

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/** VLC-style relative swipe seeking with a stable, forced preview position. */
object SlideSeekMath {

    private const val BASE_JUMP_MS = 3_000.0
    private const val MAX_JUMP_MS = 600_000.0
    private const val MIN_TRAVEL_CM = 1.0

    fun calculateTargetMs(
        startPositionMs: Long,
        durationMs: Long,
        horizontalDistancePx: Float,
        verticalDistancePx: Float,
        viewportHeightPx: Float,
        xDpi: Float
    ): Long {
        if (durationMs <= 0L || !horizontalDistancePx.isFinite()) {
            return startPositionMs.coerceAtLeast(0L)
        }

        val safeDpi = if (xDpi.isFinite() && xDpi > 0f) xDpi else 160f
        val distanceCm = horizontalDistancePx / safeDpi * 2.54f
        if (abs(distanceCm) < MIN_TRAVEL_CM) {
            return clampBeforeEof(startPositionMs, durationMs)
        }

        // VLC damps horizontal seeking when the finger also drifts vertically.
        val verticalSegmentPx = (viewportHeightPx / 32f).coerceAtLeast(1f)
        val verticalCoefficient = (abs(verticalDistancePx) / verticalSegmentPx)
            .roundToInt()
            .coerceAtLeast(1)

        val distanceTerm = (distanceCm.toDouble() / 8.0).pow(4.0)
        val jumpMagnitude = (BASE_JUMP_MS + MAX_JUMP_MS * distanceTerm) / verticalCoefficient
        val jumpMs = (sign(distanceCm.toDouble()) * jumpMagnitude)
            .coerceIn(-MAX_JUMP_MS, MAX_JUMP_MS)
            .toLong()

        return clampBeforeEof(startPositionMs + jumpMs, durationMs)
    }

    private fun clampBeforeEof(positionMs: Long, durationMs: Long): Long {
        // Landing exactly on EOF can produce an END event followed by a position
        // reset. Keep a small guard, proportional for short clips.
        val endGuardMs = min(1_000L, durationMs / 20L)
        val latestTargetMs = (durationMs - endGuardMs).coerceAtLeast(0L)
        return positionMs.coerceIn(0L, latestTargetMs)
    }
}
