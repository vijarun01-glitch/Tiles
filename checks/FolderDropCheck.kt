package com.matte.launcher

import kotlin.random.Random

fun main() {
    val random = Random(1333)
    repeat(600) {
        val requests = List(random.nextInt(0, 24)) {
            val w = random.nextInt(1, 5)
            val fixed = random.nextBoolean()
            HomeGrid.Request(if (fixed) random.nextInt(0, 5 - w) else 0, w, random.nextInt(1, 4), fixed)
        }
        val snapshot = requests.toList()
        val width = random.nextInt(1, 5)
        val incoming = HomeGrid.Request(0, width, random.nextInt(1, 4), true, 0)
        val target = HomeGrid.Position(random.nextInt(0, 5 - width), random.nextInt(0, 16))
        val preview = FolderDrop.place(requests, null, incoming, target)
        check(requests == snapshot)
        check(preview == FolderDrop.place(requests, null, incoming, target))
        check(preview.last() == target)
        val all = requests + incoming
        check(preview.size == all.size)
        for (a in all.indices) {
            check(preview[a].column >= 0 && preview[a].column + all[a].width <= 4)
            for (b in 0 until a) {
                check(preview[a].column + all[a].width <= preview[b].column ||
                    preview[b].column + all[b].width <= preview[a].column ||
                    preview[a].row + all[a].height <= preview[b].row ||
                    preview[b].row + all[b].height <= preview[a].row)
            }
        }
    }
    val requests = listOf(HomeGrid.Request(0, 1, 1, true, 0), HomeGrid.Request(1, 1, 1, true, 0))
    val swapped = FolderDrop.place(requests, 0, requests[0], HomeGrid.Position(1, 0))
    check(swapped == listOf(HomeGrid.Position(1, 0), HomeGrid.Position(0, 0)))
    println("Passed 600 cross-folder preview/commit, bounds, no-overlap, cancellation immutability and same-folder swap checks.")
}
