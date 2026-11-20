package com.matte.launcher

import kotlin.random.Random

fun main() {
    fun validate(requests: List<HomeGrid.Request>, result: List<HomeGrid.Position>) {
        val cells = HashSet<Pair<Int, Int>>()
        requests.forEachIndexed { i, request ->
            check(result[i].column >= 0 && result[i].row >= 0)
            for (x in result[i].column until result[i].column + request.width)
                for (y in result[i].row until result[i].row + request.height)
                    check(cells.add(x to y)) { "Overlap at $x, $y" }
        }
    }
    val small = listOf(HomeGrid.Request(0, 1, 1, true, 0), HomeGrid.Request(2, 1, 1, true, 3))
    val original = HomeGrid.pack(small)
    val preview = HomeGrid.dragPlacement(small, 0, original[1], original)
    check(preview == listOf(original[1], original[0]))
    check(HomeGrid.pack(small) == original) // A cancelled preview cannot change saved positions.
    check(HomeGrid.dragPlacement(small, 0, original[1], original) == preview)

    val random = Random(1328)
    repeat(50) {
        val requests = List(24) {
            val width = random.nextInt(1, 5)
            HomeGrid.Request(random.nextInt(5 - width), width, random.nextInt(1, 5), true)
        }
        var current = HomeGrid.pack(requests)
        repeat(40) {
            val selected = random.nextInt(requests.size)
            val target = if (random.nextBoolean()) current[random.nextInt(current.size)]
                else HomeGrid.Position(random.nextInt(5 - requests[selected].width), random.nextInt(20))
            val before = current.toList()
            val a = HomeGrid.dragPlacement(requests, selected, target, current)
            val b = HomeGrid.dragPlacement(requests, selected, target, current)
            check(a == b && current == before)
            check(a[selected] == target)
            validate(requests, a)
            val saved = requests.mapIndexed { i, r -> r.copy(column = a[i].column, row = a[i].row, fixedColumn = true) }
            check(HomeGrid.pack(saved) == a)
            current = a
        }
    }
    println("Passed equal-size swap, cancel/commit consistency, and 2,000 mixed-size drag previews and reloads.")
}
