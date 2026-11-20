package com.matte.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.view.DragEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Finger-following pages with eased settling and edge paging during a native tile drag. */
internal class FolderPager(context: Context) : HorizontalScrollView(context) {
    var canDragTile: () -> Boolean = { false }
    private val config = ViewConfiguration.get(context)
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0
    private var startPage = 0
    private var horizontal = false
    private var vertical = false
    private var tracker: VelocityTracker? = null
    private var settling: ValueAnimator? = null
    private var draggingTile = false
    private var edgeDirection = 0
    private val edgeWidth = 38f * resources.displayMetrics.density
    private val edgePage = object : Runnable {
        override fun run() {
            if (!draggingTile || edgeDirection == 0 || !isAttachedToWindow) return
            val current = page()
            val next = (current + edgeDirection).coerceIn(0, count() - 1)
            if (next != current) {
                settle(next)
                postDelayed(this, 650L)
            }
        }
    }
    private fun count() = ((getChildAt(0)?.width ?: width) / width.coerceAtLeast(1)).coerceAtLeast(1)
    private fun page() = (scrollX.toFloat() / width.coerceAtLeast(1)).roundToInt()
    private fun begin(event: MotionEvent) {
        settling?.cancel(); settling = null
        downX = event.x; downY = event.y; downScroll = scrollX; startPage = page()
        horizontal = false; vertical = false
        tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }
    private fun settle(page: Int) {
        settling?.cancel()
        val end = page.coerceIn(0, count() - 1) * width
        if (end == scrollX) return
        if (!ValueAnimator.areAnimatorsEnabled()) { scrollTo(end, 0); return }
        settling = ValueAnimator.ofInt(scrollX, end).apply {
            duration = 360L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (draggingTile) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event)
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dx = abs(event.x - downX); val dy = abs(event.y - downY)
                if (!horizontal && dy > config.scaledTouchSlop && dy > dx) vertical = true
                if (!vertical && dx > config.scaledTouchSlop && dx > dy * 1.2f) horizontal = true
                if (horizontal) { parent?.requestDisallowInterceptTouchEvent(true); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle(); tracker = null
            }
        }
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (draggingTile) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) begin(event)
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = downX - event.x
                if (!vertical && abs(dx) > config.scaledTouchSlop && abs(dx) > abs(event.y - downY)) horizontal = true
                if (horizontal) scrollTo((downScroll + dx.roundToInt()).coerceIn(0, (count() - 1) * width), 0)
            }
            MotionEvent.ACTION_UP -> {
                tracker?.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
                val velocity = -(tracker?.xVelocity ?: 0f)
                val distance = downX - event.x
                val target = if (horizontal && abs(velocity) > config.scaledMinimumFlingVelocity && abs(distance) > config.scaledTouchSlop)
                    startPage + if (velocity > 0) 1 else -1
                else if (horizontal && abs(distance) > width * .18f) startPage + if (distance > 0) 1 else -1
                else page()
                settle(target)
                tracker?.recycle(); tracker = null
                horizontal = false
            }
            MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle(); tracker = null
                horizontal = false
                if (!canDragTile()) settle(page())
            }
        }
        return true
    }
    override fun fling(velocityX: Int) = settle(page() + if (velocityX > 0) 1 else -1)

    override fun dispatchDragEvent(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                draggingTile = canDragTile()
                if (draggingTile) {
                    settling?.cancel(); settling = null
                    tracker?.recycle(); tracker = null
                    horizontal = false
                }
            }
            DragEvent.ACTION_DRAG_LOCATION -> if (draggingTile) {
                val direction = when {
                    event.x < edgeWidth -> -1
                    event.x > width - edgeWidth -> 1
                    else -> 0
                }
                if (direction != edgeDirection) {
                    removeCallbacks(edgePage)
                    edgeDirection = direction
                    if (direction != 0) postDelayed(edgePage, 320L)
                }
            }
            DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                removeCallbacks(edgePage); edgeDirection = 0
                if (event.action == DragEvent.ACTION_DRAG_ENDED) { draggingTile = false; settle(page()) }
            }
        }
        return super.dispatchDragEvent(event)
    }
    override fun onDragEvent(event: DragEvent): Boolean = draggingTile && event.action != DragEvent.ACTION_DROP
    fun stopPaging() {
        removeCallbacks(edgePage); edgeDirection = 0; draggingTile = false
        settling?.cancel(); settling = null
        tracker?.recycle(); tracker = null
    }
    override fun onDetachedFromWindow() { stopPaging(); super.onDetachedFromWindow() }
}
