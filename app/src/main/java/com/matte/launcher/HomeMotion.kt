package com.matte.launcher

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
