package arc.snora.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.PointTransformer
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private class PR(val x: Float, val y: Float, val r: Float = 0f)

private fun custom(pts: List<PR>, reps: Int, mirror: Boolean): RoundedPolygon {
    val c = 0.5f
    val m = pts.map { Triple(atan2(it.y - c, it.x - c), hypot(it.x - c, it.y - c), it.r) }
    val total = if (mirror) reps * 2 else reps
    val sec = 2 * PI.toFloat() / total
    val v = ArrayList<Float>()
    val rs = ArrayList<CornerRounding>()
    for (r in 0 until total) for (k in pts.indices) {
        val odd = mirror && r % 2 == 1
        val i = if (odd) pts.lastIndex - k else k
        if (odd && i == 0) continue
        val a = sec * r + if (odd) sec - m[i].first + 2 * m[0].first else m[i].first
        v += c + cos(a) * m[i].second
        v += c + sin(a) * m[i].second
        rs += CornerRounding(m[i].third)
    }
    return RoundedPolygon(v.toFloatArray(), perVertexRounding = rs, centerX = c, centerY = c)
}

private fun rotate(deg: Float, yScale: Float = 1f) = PointTransformer { x, y ->
    val a = Math.toRadians(deg.toDouble())
    val yy = y * yScale
    TransformResult(
        (x * cos(a) - yy * sin(a)).toFloat(),
        (x * sin(a) + yy * cos(a)).toFloat()
    )
}

private val polygons by lazy {
    listOf(
        custom(listOf(PR(.193f, .277f, .053f), PR(.176f, .055f, .053f)), 10, false).normalized(),
        RoundedPolygon.star(9, 1f, .8f, CornerRounding(.5f)).transformed(rotate(-90f)).normalized(),
        custom(listOf(PR(.5f, -.009f, .172f), PR(1.03f, .365f, .164f), PR(.828f, .97f, .169f)), 1, true).normalized(),
        custom(listOf(PR(.961f, .039f, .426f), PR(1.001f, .428f), PR(1f, .609f, 1f)), 2, true).normalized(),
        RoundedPolygon.star(8, 1f, .8f, CornerRounding(.15f)).normalized(),
        custom(listOf(PR(1.237f, 1.236f, .258f), PR(.5f, .918f, .233f)), 4, false).normalized(),
        RoundedPolygon.circle(8).transformed(rotate(-45f, .64f)).normalized()
    )
}

private val morphs by lazy {
    polygons.mapIndexed { i, p -> Morph(p, polygons[(i + 1) % polygons.size]) }
}

private val scaleFactor by lazy {
    polygons.fold(1f) { acc, p ->
        val b = p.calculateBounds()
        val mb = p.calculateMaxBounds()
        min(acc, max((b[2] - b[0]) / (mb[2] - mb[0]), (b[3] - b[1]) / (mb[3] - mb[1])))
    }
}

@Composable
fun ContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    containerColor: Color? = null,
    indicatorColor: Color? = null
) {
    val dark = isSystemInDarkTheme()
    val container = containerColor ?: if (dark) Color(0xFF4F378B) else Color(0xFFEADDFF)
    val indicator = indicatorColor ?: if (dark) Color(0xFFEADDFF) else Color(0xFF21005D)

    val global by rememberInfiniteTransition(label = "g").animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(4666, easing = LinearEasing)),
        label = "g"
    )
    var done by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            coroutineScope {
                launch { progress.animateTo(1f, spring(0.6f, 200f, 0.1f)) }
                delay(650)
            }
            done++
            progress.snapTo(0f)
        }
    }

    val path = remember { Path() }
    Canvas(modifier.size(size)) {
        val p = progress.value
        val n = done
        drawCircle(container, radius = this.size.minDimension / 2)

        path.rewind()
        val activeMorphs = morphs
        val currentMorph = activeMorphs[n % activeMorphs.size]
        currentMorph.asCubics(p).forEachIndexed { i, c ->
            if (i == 0) path.moveTo(c.anchor0X, c.anchor0Y)
            path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
        }
        path.close()

        val b = path.getBounds()
        val s = 38f * scaleFactor * (this.size.minDimension / 48f)
        val rot = p * 90f + 90f + 90f * n + global

        withTransform({
            translate(this.size.width / 2, this.size.height / 2)
            rotate(rot, Offset.Zero)
            scale(s, s, Offset.Zero)
            translate(-b.center.x, -b.center.y)
        }) { drawPath(path, indicator) }
    }
}
