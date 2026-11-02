package arc.snora.player

import kotlin.math.abs
import kotlin.math.min

/**
 * Cloudstream-compatible relative slide seeking.
 *
 * Adapted from recloudstream/cloudstream's PlayerGestureHelper (GPL-3.0):
 * horizontal travel is normalized by screen width, doubled, then applied as a
 * signed quadratic fraction of the media duration. Vertical drift and physical
 * DPI deliberately do not participate.
 *
 * SNORA retains a small end-of-file guard to avoid an END event followed by a
 * position reset when a long swipe lands exactly on the media duration.
 */
object SlideSeekMath {
    const val MINIMUM_SEEK_DELTA_MS = 7_000L
    private const val HORIZONTAL_MULTIPLIER = 2.0

    fun calculateTargetMs(
        startPositionMs: Long,
        durationMs: Long,
        horizontalDistancePx: Float,
        viewportWidthPx: Float
    ): Long {
        if (durationMs <= 0L ||
            !horizontalDistancePx.isFinite() ||
            !viewportWidthPx.isFinite() ||
            viewportWidthPx <= 0f
        ) {
            return startPositionMs.coerceAtLeast(0L)
        }

        val diffX = horizontalDistancePx.toDouble() * HORIZONTAL_MULTIPLIER /
            viewportWidthPx.toDouble()
        val direction = if (diffX < 0.0) -1.0 else 1.0
        val deltaMs = (durationMs.toDouble() * diffX * diffX * direction).toLong()
        return clampBeforeEof(startPositionMs + deltaMs, durationMs)
    }

    /** Cloudstream rounds only the dragging preview start down to whole seconds. */
    fun previewStartPositionMs(positionMs: Long): Long {
        return positionMs.coerceAtLeast(0L) / 1_000L * 1_000L
    }

    /** The real seek is release-only and strictly greater than Cloudstream's 7 s minimum. */
    fun shouldSeekOnRelease(startPositionMs: Long, targetPositionMs: Long): Boolean {
        return abs(targetPositionMs - startPositionMs) > MINIMUM_SEEK_DELTA_MS
    }

    private fun clampBeforeEof(positionMs: Long, durationMs: Long): Long {
        val endGuardMs = min(1_000L, durationMs / 20L)
        val latestTargetMs = (durationMs - endGuardMs).coerceAtLeast(0L)
        return positionMs.coerceIn(0L, latestTargetMs)
    }
}
