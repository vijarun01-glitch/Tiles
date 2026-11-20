package com.matte.launcher

import kotlin.random.Random

fun main() {
    fun req(c: Int, w: Int = 1, h: Int = 1, fixed: Boolean = false, row: Int? = null) =
        HomeGrid.Request(c, w, h, fixed, row)
    fun assertLayout(requests: List<HomeGrid.Request>, positions: List<HomeGrid.Position>) {
        val cells = HashSet<Pair<Int, Int>>()
        requests.forEachIndexed { index, request ->
            val position = positions[index]
            check(position.column >= 0 && position.row >= 0)
            for (x in position.column until position.column + request.width)
                for (y in position.row until position.row + request.height)
                    check(cells.add(x to y)) { "Overlapping tile at $x, $y" }
        }
    }
    fun anchored(requests: List<HomeGrid.Request>, positions: List<HomeGrid.Position>) =
        requests.mapIndexed { index, request ->
            request.copy(column = positions[index].column, row = positions[index].row, fixedColumn = true)
        }

    // Legacy packing and continuous horizontal geometry remain compatible.
    val legacy = List(4) { req(0) }
    check(HomeGrid.pack(legacy).map { it.column } == listOf(0, 1, 2, 3))
    check(HomeGrid.pack(legacy + req(3, 2, 2, true)).last() == HomeGrid.Position(3, 1))
    val end = HomeGrid.pack(legacy + req(4, 2, 2)).last()
    check(end == HomeGrid.Position(4, 0))
    check(HomeGrid.width(390, 12, 87, 6, end.column + 2) - (12 + end.column * 93 + 180) == 12)
    check(HomeGrid.width(390, 12, 87, 6, 0) == 390)

    // Explicit rows survive packing, including gaps and newly auto-packed tiles.
    val saved = listOf(req(0), req(2, 2, 2, true, 5), req(0, 1, 1, true, 0))
    val savedPositions = HomeGrid.pack(saved)
    check(savedPositions[1] == HomeGrid.Position(2, 5))
    check(savedPositions[2] == HomeGrid.Position(0, 0))
    check(savedPositions[0] == HomeGrid.Position(1, 0))

    // An occupied destination pushes its neighbor down, retaining unrelated tiles.
    val crowded = listOf(req(0, row = 0), req(1, row = 0), req(1, row = 1), req(3, row = 4))
    val moved = HomeGrid.relocate(crowded, 0, HomeGrid.Position(1, 0))
    check(moved == listOf(HomeGrid.Position(1, 0), HomeGrid.Position(1, 2),
        HomeGrid.Position(1, 1), HomeGrid.Position(3, 4)))
    check(HomeGrid.pack(anchored(crowded, moved)) == moved)

    val single = listOf(req(3, 2, 2, true, 3))
    for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
        val target = HomeGrid.Position(3 + dx, 3 + dy)
        check(HomeGrid.relocate(single, 0, target).single() == target)
    }

    check(TileSize.choices.size == 32 && TileSize.choices.toSet().size == 32)
    for (size in TileSize.choices) check(TileSize.fromKey(size.key) == size)
    check(TileSize.fromKey("m") == TileSize(2, 2))
    check(TileSize.fromKey("wide8") == TileSize(8, 2))
    check(TileSize.fromKey("g") == TileSize(4, 1))

    // Mixed dimensions, exact moves, resizes and reloads must not overlap or drift.
    val random = Random(19)
    repeat(20) {
        var requests = List(35) { req(random.nextInt(16), random.nextInt(1, 9), random.nextInt(1, 5), random.nextBoolean()) }
        var positions = HomeGrid.pack(requests)
        assertLayout(requests, positions)
        repeat(70) {
            requests = anchored(requests, positions)
            val index = random.nextInt(requests.size)
            val origin = positions[index]
            val target = if (random.nextBoolean()) {
                val size = TileSize.choices.random(random)
                requests = requests.toMutableList().apply {
                    set(index, get(index).copy(width = size.width, height = size.height))
                }
                origin
            } else HomeGrid.Position((origin.column + random.nextInt(-1, 2)).coerceAtLeast(0),
                (origin.row + random.nextInt(-1, 2)).coerceAtLeast(0))
            positions = HomeGrid.relocate(requests, index, target, positions)
            check(positions[index] == target)
            assertLayout(requests, positions)
            requests = anchored(requests, positions)
            check(HomeGrid.pack(requests) == positions)
        }
    }
    println("Passed legacy migration, four directions, collisions, saved rows, 32 sizes, canvas bounds and 1,400 move/resize/reload checks.")
}
