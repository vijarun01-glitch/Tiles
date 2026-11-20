package com.matte.launcher

import android.view.View

/** One geometry for Home, app opening and locking. */
internal object TileTurnGeometry {
    fun prepare(view: View, screenX: Int, canvasWidth: Int) {
        view.pivotX = -screenX.toFloat()
        view.pivotY = view.height / 2f
        view.cameraDistance = canvasWidth * 8f
    }
}
