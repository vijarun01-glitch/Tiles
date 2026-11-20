package com.matte.launcher

import kotlin.math.abs
import kotlin.math.hypot

fun main() {
    val origin = LockTurn.Point(400f, 240f)
    check(LockTurn.timings(emptyList(), origin).isEmpty())
    val centers = (0..3).flatMap { x -> (0..7).map { y -> LockTurn.Point(50f + x * 100, 50f + y * 100) } }
    for (point in listOf(origin, LockTurn.Point(400f, 700f), LockTurn.Point(1200f, 500f))) {
        val timings = LockTurn.timings(centers, point)
        val sorted = centers.indices.sortedBy { hypot(centers[it].x - point.x, centers[it].y - point.y) }
        check(timings[sorted.first()].startMs == 0L)
        check(timings[sorted.last()].startMs == 36L)
        check(sorted.zipWithNext().all { (a, b) -> timings[a].startMs <= timings[b].startMs })
        for (timing in timings) {
            check(timing.finishMs <= 166L)
            check(LockTurn.angle(timing.startMs.toFloat(), timing) == 0f)
            check(LockTurn.opacity(timing.startMs.toFloat(), timing) == 1f)
            check(LockTurn.angle(timing.finishMs.toFloat(), timing) == 65f)
            check(LockTurn.opacity(timing.finishMs.toFloat(), timing) == 0f)
            var lastAngle = 0f
            var lastOpacity = 1f
            for (time in 0..200) {
                val angle = LockTurn.angle(time.toFloat(), timing)
                val opacity = LockTurn.opacity(time.toFloat(), timing)
                check(angle in lastAngle..65f)
                check(opacity in 0f..lastOpacity)
                // Same curve as the app-opening reverse of HomeMotion with its original direction.
                val expected = HomeMotion.turnAngle(timing.finishMs + timing.startMs - time.toFloat(), timing)
                check(abs(angle - expected) < 0.0001f)
                check(abs(opacity - (1f - angle / 65f)) < 0.0001f)
                lastAngle = angle
                lastOpacity = opacity
            }
            check(LockTurn.opacity(Float.MAX_VALUE, timing) == 0f)
        }
    }
    val low = LockTurn.timings(centers, LockTurn.Point(400f, 700f))
    val high = LockTurn.timings(centers, origin)
    check(low != high)
    check(LockTurn.timings(listOf(origin), origin).single() == HomeMotion.TurnTiming(0L, 130L))
    val shift = LockTurn.Point(80f, 120f)
    val moved = LockTurn.timings(centers.map { LockTurn.Point(it.x + shift.x, it.y + shift.y) },
        LockTurn.Point(origin.x + shift.x, origin.y + shift.y))
    check(moved == high)
    println("Passed button-distance ordering, position adjustment, translated coordinates, app-turn curve, fade and duration checks.")
}
