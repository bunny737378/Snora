package arc.snora.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideSeekMathTest {

    @Test
    fun unknownDurationDoesNotClampToBeginning() {
        val target = SlideSeekMath.calculateTargetMs(
            startPositionMs = 45_000L,
            durationMs = 0L,
            horizontalDistancePx = 200f,
            viewportWidthPx = 1_000f
        )

        assertEquals(45_000L, target)
    }

    @Test
    fun cloudstreamQuadraticCurveIsSignedAndDurationBased() {
        // diffX = 100 * 2 / 1000 = 0.2; delta = 600000 * 0.2² = 24000.
        val forward = target(start = 100_000L, horizontalPx = 100f)
        val backward = target(start = 100_000L, horizontalPx = -100f)

        assertEquals(124_000L, forward)
        assertEquals(76_000L, backward)
    }

    @Test
    fun smallTravelStillUpdatesPreviewWithoutPhysicalDistanceThreshold() {
        val target = target(start = 100_000L, horizontalPx = 10f)

        assertEquals(100_240L, target)
    }

    @Test
    fun largeForwardSwipeNeverLandsExactlyOnEof() {
        val target = target(start = 590_000L, horizontalPx = 2_000f)

        assertEquals(599_000L, target)
    }

    @Test
    fun previewStartIsRoundedDownToWholeSeconds() {
        assertEquals(123_000L, SlideSeekMath.previewStartPositionMs(123_987L))
        assertEquals(0L, SlideSeekMath.previewStartPositionMs(-10L))
    }

    @Test
    fun releaseThresholdIsStrictlyGreaterThanSevenSeconds() {
        assertFalse(SlideSeekMath.shouldSeekOnRelease(100_000L, 107_000L))
        assertTrue(SlideSeekMath.shouldSeekOnRelease(100_000L, 107_001L))
        assertTrue(SlideSeekMath.shouldSeekOnRelease(100_000L, 92_999L))
    }

    @Test
    fun invalidViewportKeepsStartPosition() {
        val target = SlideSeekMath.calculateTargetMs(
            startPositionMs = 45_000L,
            durationMs = 600_000L,
            horizontalDistancePx = 200f,
            viewportWidthPx = 0f
        )

        assertEquals(45_000L, target)
    }

    private fun target(start: Long, horizontalPx: Float): Long {
        return SlideSeekMath.calculateTargetMs(
            startPositionMs = start,
            durationMs = 600_000L,
            horizontalDistancePx = horizontalPx,
            viewportWidthPx = 1_000f
        )
    }
}
