package com.matte.launcher

/** The title is fixed at the top; only the grid rises as it grows. */
internal object FolderLayout {
    data class Placement(val titleTop: Int, val tilesTop: Int)

    fun place(viewportHeight: Int, titleHeight: Int, contentHeight: Int,
              topPadding: Int, titleGap: Int, bottomPadding: Int): Placement {
        val titleTop = 0
        val minimumTilesTop = titleHeight + titleGap
        val restingTop = (viewportHeight / 2).coerceAtLeast(minimumTilesTop)
        val roomBelow = (viewportHeight - restingTop - bottomPadding).coerceAtLeast(0)
        val lift = (contentHeight - roomBelow).coerceAtLeast(0)
        val top = (restingTop - lift).coerceAtLeast(minimumTilesTop)
        return Placement(titleTop, top)
    }
}
