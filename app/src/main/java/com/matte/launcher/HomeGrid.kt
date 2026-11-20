package com.matte.launcher

internal object HomeGrid {
    const val COLUMNS_PER_VIEWPORT = 4

    data class Request(val column: Int, val width: Int, val height: Int, val fixedColumn: Boolean,
                       val row: Int? = null)
    data class Position(val column: Int, val row: Int)

    fun pack(requests: List<Request>): List<Position> {
        val positions = arrayOfNulls<Position>(requests.size)
        val placed = ArrayList<Int>()
        // Reserve saved positions before filling gaps with newly added or legacy tiles.
        for (index in requests.indices.sortedBy { if (requests[it].row == null) 1 else 0 }) {
            val request = requests[index]
            require(request.column >= 0 && request.width > 0 && request.height > 0)
            val lastColumn = if (request.fixedColumn || request.row != null) request.column else {
                request.column + (COLUMNS_PER_VIEWPORT - request.width).coerceAtLeast(0)
            }
            var row = request.row?.coerceAtLeast(0) ?: 0
            var column: Int? = null
            while (column == null) {
                column = (request.column..lastColumn).firstOrNull { start ->
                    placed.none { other ->
                        overlaps(request, Position(start, row), requests[other], positions[other]!!)
                    }
                }
                if (column == null) row++
            }
            positions[index] = Position(column, row)
            placed.add(index)
        }
        return positions.map { it!! }
    }

    /** Move the selected tile exactly; retain other positions and push colliding tiles down. */
    fun relocate(requests: List<Request>, selected: Int, target: Position,
                 current: List<Position> = pack(requests)): List<Position> {
        require(selected in requests.indices && current.size == requests.size)
        require(target.column >= 0 && target.row >= 0)
        val anchored = requests.mapIndexed { index, request ->
            val position = if (index == selected) target else current[index]
            request.copy(column = position.column, row = position.row, fixedColumn = true)
        }
        // Unaffected neighbors reserve their cells before displaced tiles find free rows.
        val order = requests.indices.sortedBy { index ->
            when {
                index == selected -> 0
                !overlaps(anchored[selected], target, anchored[index], current[index]) -> 1
                else -> 2
            }
        }
        val packed = pack(order.map { anchored[it] })
        val result = current.toMutableList()
        order.forEachIndexed { index, original -> result[original] = packed[index] }
        return result
    }

    /** Drag previews and commits share this result. Equal footprints exchange places. */
    fun dragPlacement(requests: List<Request>, selected: Int, target: Position,
                      current: List<Position> = pack(requests)): List<Position> {
        require(selected in requests.indices && current.size == requests.size)
        val moving = requests[selected]
        val hit = requests.indices.filter { it != selected && overlaps(moving, target, requests[it], current[it]) }
        if (hit.size == 1) {
            val other = hit.single()
            val sameSize = moving.width == requests[other].width && moving.height == requests[other].height
            if (sameSize && current[other] == target) {
                return current.toMutableList().apply {
                    set(selected, target)
                    set(other, current[selected])
                }
            }
        }
        return relocate(requests, selected, target, current)
    }

    private fun overlaps(a: Request, ap: Position, b: Request, bp: Position): Boolean =
        ap.column < bp.column + b.width && ap.column + a.width > bp.column &&
            ap.row < bp.row + b.height && ap.row + a.height > bp.row

    fun width(viewport: Int, edge: Int, tile: Int, gap: Int, occupiedColumns: Int): Int =
        maxOf(viewport, edge * 2 + occupiedColumns * (tile + gap) - gap)
}
