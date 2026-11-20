package com.matte.launcher

fun main() {
    for (height in listOf(480, 720, 900, 1200, 2400)) {
        val title = 186
        val empty = FolderLayout.place(height, title, 0, 64, 20, 16)
        check(empty.titleTop == 0)
        check(empty.tilesTop == maxOf(height / 2, title + 20))
        val room = (height - empty.tilesTop - 16).coerceAtLeast(0)
        check(FolderLayout.place(height, title, room, 64, 20, 16) == empty)
        var previous = empty.tilesTop
        for (content in 0..4000) {
            val p = FolderLayout.place(height, title, content, 64, 20, 16)
            check(p.titleTop == 0)
            check(p.tilesTop in (title + 20)..previous)
            if (p.tilesTop > title + 20 && content > room) check(p.tilesTop + content + 16 == height)
            previous = p.tilesTop
        }
        check(previous == title + 20)
        check(FolderLayout.place(height, title, 0, 64, 20, 16) == empty)
    }
    println("Passed fixed folder header, midpoint start, grid growth, top clamp and shrinking checks across five screen heights.")
}
