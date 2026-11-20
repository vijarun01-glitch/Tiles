/**package com.matte.launcher

import kotlin.math.abs
import kotlin.math.roundToInt

internal object HomeMotion {
    const val DEFAULT_TOTAL_MS = 700
    const val MIN_TOTAL_MS = 300
    const val MAX_TOTAL_MS = 2000
    const val DURATION_STEP_MS = 25
    private const val START_ANGLE = 65f

    fun swipeSettle(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return (1f - t) * (1f - t) * kotlin.math.cos(t * 7f)
    }

    data class VisibleTile(val right: Int, val bottom: Int, val inDock: Boolean)

    data class TurnTiming(val startMs: Long, val durationMs: Long) {
        val finishMs: Long get() = startMs + durationMs
    }

    fun turnTimings(tiles: List<VisibleTile>, cellPitch: Int, totalMs: Int, first: Int? = null): List<TurnTiming> {
        if (tiles.isEmpty()) return emptyList()
        val total = totalMs.coerceIn(MIN_TOTAL_MS, MAX_TOTAL_MS).toLong()
        if (tiles.size == 1) return listOf(TurnTiming(0L, total))
        val pitch = cellPitch.coerceAtLeast(1).toFloat()
        val lead = first ?: tiles.indices.maxWith(compareBy({ tiles[it].bottom }, { tiles[it].right }))
        val leader = tiles[lead]
        val gridBottom = tiles.filterNot { it.inDock }.maxOfOrNull { it.bottom }

        val diagonal = tiles.mapIndexed { index, tile ->
            if (index == lead) return@mapIndexed 0
            val columns = (abs(leader.right - tile.right) / pitch).roundToInt()
            val rows = if (leader.inDock && !tile.inDock && gridBottom != null) {
                1 + ((gridBottom - tile.bottom) / pitch).roundToInt()
            } else {
                (abs(leader.bottom - tile.bottom) / pitch).roundToInt()
            }
            (columns + rows).coerceAtLeast(1)
        }
        val waves = diagonal.distinct().sorted().withIndex().associate { it.value to it.index }
        val lastWave = waves.size - 1
        val firstFinish = total * 3L / 10L
        return diagonal.map { index ->
            val wave = waves.getValue(index)
            // The last wave begins when the first tile lands. Both endpoints stay exact.
            val start = (firstFinish * wave + lastWave / 2L) / lastWave
            val finish = firstFinish + ((total - firstFinish) * wave + lastWave / 2L) / lastWave
            TurnTiming(start, finish - start)
        }
    }

    fun turnAngle(elapsedMs: Float, timing: TurnTiming): Float {
        val progress = ((elapsedMs - timing.startMs) / timing.durationMs).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        return START_ANGLE * (1f - eased)
    }
}
*/

package com.matte.launcher

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

internal object HomeMotion {
    const val DEFAULT_TOTAL_MS = 700
    const val MIN_TOTAL_MS = 300
    const val MAX_TOTAL_MS = 2000
    const val DURATION_STEP_MS = 25
    const val START_ANGLE = 65f

    /**
     * Damped oscillation for swipe gestures: `progress` in `0f..1f` settles smoothly to `0f`.
     */
    fun swipeSettle(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        val damped = (1f - t) * (1f - t)
        return damped * cos(t * 7.0).toFloat()
    }

    /**
     * Converts a normalized swipe settle factor (`-1f..1f`) into a gentle turn angle in degrees.
     */
    fun swipeSettleAngle(progress: Float, maxAngle: Float = START_ANGLE * 0.25f): Float =
        swipeSettle(progress) * maxAngle

    data class VisibleTile(val right: Int, val bottom: Int, val inDock: Boolean)

    data class TurnTiming(val startMs: Long, val durationMs: Long) {
        val finishMs: Long get() = startMs + durationMs
    }

    fun turnTimings(
        tiles: List<VisibleTile>,
        cellPitch: Int,
        totalMs: Int,
        first: Int? = null
    ): List<TurnTiming> {
        if (tiles.isEmpty()) return emptyList()
        val total = totalMs.coerceIn(MIN_TOTAL_MS, MAX_TOTAL_MS).toLong()
        if (tiles.size == 1) return listOf(TurnTiming(0L, total))

        val pitch = cellPitch.coerceAtLeast(1).toFloat()
        val lead = first?.coerceIn(0, tiles.lastIndex)
            ?: tiles.indices.maxWith(compareBy({ tiles[it].bottom }, { tiles[it].right }))
        val leader = tiles[lead]
        val gridBottom = tiles.filterNot { it.inDock }.maxOfOrNull { it.bottom }

        val diagonal = tiles.mapIndexed { index, tile ->
            if (index == lead) return@mapIndexed 0
            val columns = (abs(leader.right - tile.right) / pitch).roundToInt()
            val rows = if (leader.inDock && !tile.inDock && gridBottom != null) {
                1 + ((gridBottom - tile.bottom) / pitch).roundToInt()
            } else {
                (abs(leader.bottom - tile.bottom) / pitch).roundToInt()
            }
            (columns + rows).coerceAtLeast(1)
        }

        val waves = diagonal.distinct().sorted().withIndex().associate { it.value to it.index }
        val denom = (waves.size - 1).coerceAtLeast(1)
        val firstFinish = total * 3L / 10L
        val remainingSpan = (total - firstFinish).coerceAtLeast(0L)

        return diagonal.map { distance ->
            val wave = waves.getValue(distance)
            // The last wave begins when the first tile lands. Both endpoints stay exact.
            val start = (firstFinish * wave + denom / 2L) / denom
            val finish = firstFinish + ((remainingSpan * wave + denom / 2L) / denom)
            val safeDuration = (finish - start).coerceAtLeast(1L)
            TurnTiming(start, safeDuration)
        }
    }

    /**
     * Normalized progress (`0f..1f`) for a tile at [elapsedMs], with optional quintic smootherstep
     * for an even softer start and landing on high-refresh displays.
     */
    fun turnProgress(
        elapsedMs: Double,
        timing: TurnTiming,
        useSmootherStep: Boolean = true
    ): Float {
        val duration = timing.durationMs.coerceAtLeast(1L).toDouble()
        val raw = ((elapsedMs - timing.startMs.toDouble()) / duration).coerceIn(0.0, 1.0)
        val eased = if (useSmootherStep) {
            raw * raw * raw * (raw * (raw * 6.0 - 15.0) + 10.0)
        } else {
            raw * raw * (3.0 - 2.0 * raw)
        }
        return eased.toFloat()
    }

    /** Overload accepting [Float] elapsed time for existing callers. */
    fun turnAngle(
        elapsedMs: Float,
        timing: TurnTiming,
        startAngle: Float = START_ANGLE,
        useSmootherStep: Boolean = true
    ): Float = turnAngle(elapsedMs.toDouble(), timing, startAngle, useSmootherStep)

    /** Precision-safe overload accepting [Double] elapsed time (e.g., frame time deltas). */
    fun turnAngle(
        elapsedMs: Double,
        timing: TurnTiming,
        startAngle: Float = START_ANGLE,
        useSmootherStep: Boolean = true
    ): Float {
        val eased = turnProgress(elapsedMs, timing, useSmootherStep)
        return startAngle * (1f - eased)
    }
}
