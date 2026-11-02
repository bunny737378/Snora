package arc.snora.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideSeekMathTest {

    @Test
    fun unknownDurationDoesNotClampToBeginning() {
        val target = SlideSeekMath.calculateTargetMs(
            startPositionMs = 45_000L,
            durationMs = 0L,
            horizontalDistancePx = 200f,
            verticalDistancePx = 0f,
            viewportHeightPx = 800f,
            xDpi = 160f
        )

        assertEquals(45_000L, target)
    }

    @Test
    fun subCentimeterTravelKeepsForcedPosition() {
        val target = SlideSeekMath.calculateTargetMs(
            startPositionMs = 45_000L,
            durationMs = 600_000L,
            horizontalDistancePx = 20f,
            verticalDistancePx = 0f,
            viewportHeightPx = 800f,
            xDpi = 160f
        )

        assertEquals(45_000L, target)
    }

    @Test
    fun forwardAndBackwardJumpsAreRelativeToGestureStart() {
        val forward = target(start = 100_000L, horizontalPx = 126f)
        val backward = target(start = 100_000L, horizontalPx = -126f)

        assertTrue(forward > 100_000L)
        assertTrue(backward < 100_000L)
        assertTrue(forward - 100_000L < 10_000L)
        assertTrue(100_000L - backward < 10_000L)
    }

    @Test
    fun largeForwardSwipeNeverLandsExactlyOnEof() {
        val target = target(start = 590_000L, horizontalPx = 2_000f)

        assertEquals(599_000L, target)
    }

    @Test
    fun verticalDriftDampsHorizontalJump() {
        val straight = SlideSeekMath.calculateTargetMs(
            startPositionMs = 100_000L,
            durationMs = 600_000L,
            horizontalDistancePx = 200f,
            verticalDistancePx = 0f,
            viewportHeightPx = 800f,
            xDpi = 160f
        )
        val diagonal = SlideSeekMath.calculateTargetMs(
            startPositionMs = 100_000L,
            durationMs = 600_000L,
            horizontalDistancePx = 200f,
            verticalDistancePx = 300f,
            viewportHeightPx = 800f,
            xDpi = 160f
        )

        assertTrue(diagonal < straight)
    }

    private fun target(start: Long, horizontalPx: Float): Long {
        return SlideSeekMath.calculateTargetMs(
            startPositionMs = start,
            durationMs = 600_000L,
            horizontalDistancePx = horizontalPx,
            verticalDistancePx = 0f,
            viewportHeightPx = 800f,
            xDpi = 160f
        )
    }
}
