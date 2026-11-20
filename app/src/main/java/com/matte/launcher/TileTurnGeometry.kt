/**package com.matte.launcher

import android.view.View

/** One geometry for Home, app opening and locking. */
internal object TileTurnGeometry {
    fun prepare(view: View, screenX: Int, canvasWidth: Int) {
        view.pivotX = -screenX.toFloat()
        view.pivotY = view.height / 2f
        view.cameraDistance = canvasWidth * 8f
    }
}
*/
package com.matte.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.animation.PathInterpolator

/** One geometry for Home, app opening and locking. */
internal object TileTurnGeometry {

    // Higher multiplier = flatter, less distorted 3D turn across wide canvases.
    private const val PERSPECTIVE_MULTIPLIER = 10f
    private const val DEFAULT_DURATION_MS = 280L

    // Material standard-like cubic curve using built-in PathInterpolator (API 21+).
    private val smoothTurnInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /**
     * Prepares 3D pivot and camera depth for [view].
     * Safe to call before or after layout; updates pivotY once measured if height is 0.
     */
    fun prepare(view: View, screenX: Int, canvasWidth: Int) {
        val desiredPivotX = -screenX.toFloat()
        if (view.pivotX != desiredPivotX) {
            view.pivotX = desiredPivotX
        }

        val density = view.resources.displayMetrics.density.coerceAtLeast(1f)
        val baseSpan = if (canvasWidth > 0) canvasWidth.toFloat() else view.width.toFloat()
        val desiredCameraDistance =
            (baseSpan.coerceAtLeast(1f)) * PERSPECTIVE_MULTIPLIER * (density / 2f).coerceAtLeast(1f)
        if (view.cameraDistance != desiredCameraDistance) {
            view.cameraDistance = desiredCameraDistance
        }

        if (view.height > 0) {
            applyCenterPivotY(view)
        } else {
            view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {
                    if (v.height > 0) {
                        v.removeOnLayoutChangeListener(this)
                        applyCenterPivotY(v)
                    }
                }
            })
        }
    }

    private fun applyCenterPivotY(view: View) {
        val desiredPivotY = view.height * 0.5f
        if (view.pivotY != desiredPivotY) {
            view.pivotY = desiredPivotY
        }
    }

    /**
     * Animates rotationY smoothly with GPU-friendly layer promotion and clean teardown.
     */
    fun animateTurnY(
        view: View,
        targetRotationY: Float,
        screenX: Int,
        canvasWidth: Int,
        durationMs: Long = DEFAULT_DURATION_MS,
        startDelayMs: Long = 0L,
        onEnd: (() -> Unit)? = null
    ): ViewPropertyAnimator {
        view.animate().cancel()
        prepare(view, screenX, canvasWidth)

        if (view.isAttachedToWindow) {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        return view.animate()
            .rotationY(targetRotationY)
            .setDuration(durationMs)
            .setStartDelay(startDelayMs)
            .setInterpolator(smoothTurnInterpolator)
            .withLayer()
            .setListener(object : AnimatorListenerAdapter() {
                private var finishedCleanly = false

                override fun onAnimationEnd(animation: Animator) {
                    if (finishedCleanly) return
                    finishedCleanly = true
                    cleanupLayer(view)
                    onEnd?.invoke()
                }

                override fun onAnimationCancel(animation: Animator) {
                    cleanupLayer(view)
                }
            })
    }

    /** Resets transforms cleanly (e.g., when returning Home or unlocking). */
    fun reset(view: View) {
        view.animate().cancel()
        view.rotationX = 0f
        view.rotationY = 0f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        cleanupLayer(view)
    }

    private fun cleanupLayer(view: View) {
        if (view.layerType != View.LAYER_TYPE_NONE) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }
}
