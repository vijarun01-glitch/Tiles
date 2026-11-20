package com.matte.launcher

import kotlin.math.hypot
import kotlin.math.roundToLong

/** Original app-opening tile turn, spreading outward from the Power button. */
internal object LockTurn {
    data class Point(val x: Float, val y: Float)

    fun timings(centers: List<Point>, origin: Point): List<HomeMotion.TurnTiming> {
        if (centers.isEmpty()) return emptyList()
        val distances = centers.map { hypot(it.x - origin.x, it.y - origin.y) }
        val nearest = distances.min()
        val spread = distances.max() - nearest
        return distances.map { distance ->
            val delay = if (spread <= 0f) 0L else ((distance - nearest) / spread * 36f).roundToLong()
            HomeMotion.TurnTiming(delay, 130L)
        }
    }

    fun angle(elapsedMs: Float, timing: HomeMotion.TurnTiming): Float =
        HomeMotion.turnAngle(timing.startMs + timing.finishMs - elapsedMs, timing)

    fun opacity(elapsedMs: Float, timing: HomeMotion.TurnTiming): Float {
        val t = ((elapsedMs - timing.startMs) / timing.durationMs).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }
}
