package com.matte.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.animation.LinearInterpolator

/** Best-effort screen-off rendering. Never intercepts Power or delays the system lock. */
internal class LockTurnController(
    private val activity: Activity,
    private val tiles: () -> List<View>,
    private val enabled: () -> Boolean,
    private val buttonHeight: () -> Float,
    private val canvasWidth: () -> Int,
    private val prepare: () -> Unit,
    private val restored: () -> Unit,
    private val wake: (Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val power = activity.getSystemService(PowerManager::class.java)
    private val keyguard = activity.getSystemService(KeyguardManager::class.java)
    private val displays = activity.getSystemService(DisplayManager::class.java)
    private var foreground = false
    private var eligibleUntil = 0L
    private var animation: ValueAnimator? = null
    private var previewing = false
    private var poses = emptyList<Pose>()
    private data class Pose(val view: View, val rotationY: Float, val alpha: Float, val pivotX: Float,
                            val pivotY: Float, val cameraDistance: Float, val timing: HomeMotion.TurnTiming)
    val active get() = poses.isNotEmpty()

    private fun dark(): Boolean {
        val state = activity.window.decorView.display?.state ?: Display.STATE_UNKNOWN
        return state == Display.STATE_OFF || state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND
    }
    private fun sleeping() = !power.isInteractive || keyguard.isKeyguardLocked
    private fun eligible() = foreground || SystemClock.uptimeMillis() <= eligibleUntil
    private val probe = object : Runnable {
        override fun run() {
            if (!eligible() || previewing) return
            if (sleeping()) start(false)
            else if (!foreground && SystemClock.uptimeMillis() < eligibleUntil) handler.postDelayed(this, 8L)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (eligible()) start(false)
                    if (dark()) finishAtBlack()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    reset()
                    // Screen on (still locked) stages the tiles; unlocking plays the Home turn.
                    wake(intent.action == Intent.ACTION_USER_PRESENT)
                }
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (id != activity.window.decorView.display?.displayId) return
            if (!previewing && eligible() && sleeping()) start(false)
            if (!previewing && dark()) finishAtBlack()
        }
    }

    init {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else activity.registerReceiver(receiver, filter)
        displays.registerDisplayListener(displayListener, handler)
    }

    fun onResumed() {
        foreground = true
        eligibleUntil = 0L
        handler.removeCallbacks(probe)
        reset()
    }

    fun onPaused() {
        foreground = false
        eligibleUntil = SystemClock.uptimeMillis() + 180L
        handler.removeCallbacks(probe)
        probe.run()
    }

    fun onFocusChanged(focused: Boolean) {
        if (focused && power.isInteractive && !keyguard.isKeyguardLocked) {
            foreground = true
            handler.removeCallbacks(probe)
            if (!previewing) reset()
        } else if (!focused && foreground) {
            foreground = false
            eligibleUntil = SystemClock.uptimeMillis() + 180L
            probe.run()
        }
    }

    fun preview() { reset(); start(true) }

    private fun start(preview: Boolean) {
        if (active || (!preview && !enabled()) || !ValueAnimator.areAnimatorsEnabled()) return
        if (!preview && dark()) return // Already black: there is no visible interval to animate.
        val bounds = Rect()
        val visible = tiles().filter { it.isShown && it.width > 0 && it.height > 0 && it.getGlobalVisibleRect(bounds) }
        if (visible.isEmpty()) return
        prepare()
        previewing = preview
        val decor = activity.window.decorView
        val decorLocation = IntArray(2)
        decor.getLocationOnScreen(decorLocation)
        val origin = LockTurn.Point(decorLocation[0] + decor.width.toFloat(),
            decorLocation[1] + decor.height * buttonHeight().coerceIn(0.1f, 0.9f))
        val locations = visible.map { view -> IntArray(2).also { view.getLocationOnScreen(it) } }
        val centers = visible.mapIndexed { index, view ->
            LockTurn.Point(locations[index][0] + view.width / 2f, locations[index][1] + view.height / 2f)
        }
        val timings = LockTurn.timings(centers, origin)
        poses = visible.mapIndexed { index, view ->
            view.animate().cancel()
            Pose(view, view.rotationY, view.alpha, view.pivotX, view.pivotY,
                view.cameraDistance, timings[index]).also {
                TileTurnGeometry.prepare(view, locations[index][0], canvasWidth())
            }
        }
        val total = timings.maxOf { it.finishMs }.toFloat()
        poses.forEach { it.view.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        // Skip a few milliseconds of imperceptible motion while preserving staggered starts.
        render(8f)
        animation = ValueAnimator.ofFloat(8f, total).apply {
            duration = (total.toLong() - 8).coerceAtLeast(1)
            interpolator = LinearInterpolator()
            addUpdateListener { render(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    if (animation !== animator) return
                    animation = null
                    if (previewing) handler.postDelayed(restorePreview, 350L)
                    // Real lock: retain the turned, transparent pose until screen-on/resume, never bounce back.
                }
            })
        }
        animation?.start()
    }

    private fun render(time: Float) {
        poses.forEach {
            it.view.rotationY = it.rotationY + LockTurn.angle(time, it.timing)
            it.view.alpha = it.alpha * LockTurn.opacity(time, it.timing)
        }
    }

    private fun finishAtBlack() {
        if (previewing || !active) return
        val previous = animation
        animation = null
        previous?.cancel()
        render(Float.MAX_VALUE)
    }

    private val restorePreview = Runnable { reset(); restored() }

    fun reset() {
        handler.removeCallbacks(restorePreview)
        val previous = animation
        animation = null
        previous?.cancel()
        poses.forEach {
            it.view.setLayerType(View.LAYER_TYPE_NONE, null)
            it.view.rotationY = it.rotationY
            it.view.cameraDistance = it.cameraDistance
            it.view.alpha = it.alpha
            it.view.pivotX = it.pivotX; it.view.pivotY = it.pivotY
        }
        poses = emptyList()
        previewing = false
    }

    fun dispose() {
        handler.removeCallbacksAndMessages(null)
        reset()
        activity.unregisterReceiver(receiver)
        displays.unregisterDisplayListener(displayListener)
    }
}
