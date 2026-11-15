package amorx

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
internal fun WaveParticles(playing: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    val field = remember(color) { WaveParticleField(color) }
    val speed by animateFloatAsState(if (playing) 1.5f else 0.74f, tween(850), label = "wave tempo")
    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var previous = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsed = now - previous
                if (elapsed >= 26_000_000L) {
                    phase += (elapsed / 1_000_000_000f).coerceAtMost(0.065f) * speed
                    previous = now
                }
            }
        }
    }

    Canvas(modifier.clipToBounds()) {
        field.advance(phase, size.width, size.height)
        drawIntoCanvas { canvas ->
            field.batches.forEach { batch ->
                batch.paint.strokeWidth = batch.diameter.dp.toPx()
                canvas.drawRawPoints(PointMode.Points, batch.coordinates, batch.paint)
            }
        }
    }
}

private class WaveParticleField(color: Color) {
    private val samples = 224
    private val ribbons = 3
    private val lanes = 12
    private val center = FloatArray(samples * ribbons)
    private val height = FloatArray(samples * ribbons)
    private val spread = FloatArray(samples * ribbons)
    private val envelope = FloatArray(samples) { column ->
        0.58f + 0.42f * sin(PI * column / (samples - 1)).toFloat()
    }

    val batches: List<Batch> = buildBatches(color)

    // Sampling the three ribbon surfaces first keeps the trigonometry off the per-particle path.
    fun advance(phase: Float, width: Float, height: Float) {
        for (column in 0 until samples) {
            val x = column / (samples - 1f)
            val edge = envelope[column]
            val spine = 0.55f + (
                0.086f * sin(x * 19.0f - phase * 0.82f) +
                    0.036f * sin(x * 39.0f + phase * 0.57f)
                ) * edge
            for (ribbon in 0 until ribbons) {
                val sample = column * ribbons + ribbon
                val twist = x * 16f - phase * 1.06f + ribbon * 2.05f
                center[sample] = spine +
                    0.11f * sin(x * (11f + ribbon * 5.2f) + phase * 0.64f + ribbon * 1.8f)
                this.height[sample] = (0.036f + 0.162f * sin(twist)) * edge
                spread[sample] = 0.025f * cos(twist) * edge
            }
        }

        batches.forEach { batch ->
            var offset = 0
            batch.particles.forEach { particle ->
                batch.coordinates[offset++] =
                    (particle.x + particle.lane * spread[particle.sample]) * width
                batch.coordinates[offset++] =
                    (center[particle.sample] + particle.lane * this.height[particle.sample] + particle.jitter) * height
            }
        }
    }

    private fun buildBatches(color: Color): List<Batch> {
        val random = Random(23689)
        val groups = List(6) { ArrayList<Particle>() }
        for (column in 0 until samples) {
            for (ribbon in 0 until ribbons) {
                for (lane in 0 until lanes) {
                    val across = 2f * (lane + random.nextFloat()) / lanes - 1f
                    val brightness = random.nextFloat() * (1.08f - ribbon * 0.12f)
                    val group = (brightness * groups.size).toInt().coerceAtMost(groups.lastIndex)
                    groups[group] += Particle(
                        sample = column * ribbons + ribbon,
                        x = (column + random.nextFloat() - 0.5f) / (samples - 1f),
                        lane = across,
                        jitter = (random.nextFloat() - 0.5f) * 0.014f
                    )
                }
            }
        }
        val diameters = floatArrayOf(0.55f, 0.61f, 0.72f, 0.81f, 0.94f, 1.21f)
        val opacity = floatArrayOf(0.10f, 0.18f, 0.26f, 0.35f, 0.49f, 0.64f)
        return groups.mapIndexed { index, particles ->
            Batch(
                particles,
                diameters[index],
                Paint().apply {
                    this.color = color.copy(alpha = opacity[index])
                    isAntiAlias = true
                    strokeCap = StrokeCap.Round
                }
            )
        }
    }

    data class Particle(val sample: Int, val x: Float, val lane: Float, val jitter: Float)

    class Batch(val particles: List<Particle>, val diameter: Float, val paint: Paint) {
        val coordinates = FloatArray(particles.size * 2)
    }
}
