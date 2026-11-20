package com.matte.launcher

internal data class TileSize(val width: Int, val height: Int) {
    val key get() = "${width}x${height}"
    val label get() = "$width \u00d7 $height"

    companion object {
        val choices = (1..8).flatMap { width -> (1..4).map { height -> TileSize(width, height) } }

        fun fromKey(key: String): TileSize = when (key) {
            "s" -> TileSize(1, 1)
            "m" -> TileSize(2, 2)
            "w" -> TileSize(4, 2)
            "g" -> TileSize(4, 1)
            "l" -> TileSize(4, 4)
            "wide6" -> TileSize(6, 2)
            "wide8" -> TileSize(8, 2)
            else -> choices.firstOrNull { it.key == key } ?: TileSize(1, 1)
        }
    }
}
