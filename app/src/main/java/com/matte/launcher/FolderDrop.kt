package com.matte.launcher

/** Pure placement used by both the cross-folder preview and the final drop. */
internal object FolderDrop {
    fun place(requests: List<HomeGrid.Request>, selected: Int?, incoming: HomeGrid.Request,
              target: HomeGrid.Position): List<HomeGrid.Position> {
        val current = HomeGrid.pack(requests)
        if (selected != null) return HomeGrid.dragPlacement(requests, selected, target, current)
        val bottom = requests.indices.maxOfOrNull { current[it].row + requests[it].height } ?: 0
        val appended = incoming.copy(column = 0, row = bottom, fixedColumn = true)
        return HomeGrid.relocate(requests + appended, requests.size, target,
            current + HomeGrid.Position(0, bottom))
    }
}
