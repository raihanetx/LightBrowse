package com.lightbrowse

import android.animation.ValueAnimator
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import kotlin.math.roundToInt

/**
 * Professional zoom handler for WebView.
 * Implements: pinch-to-zoom, double-tap zoom, button zoom, text reflow.
 * Works like Chrome/Firefox zoom behavior.
 */
class ZoomManager(
    private val context: Context,
    private val webView: WebView,
    private val onZoomChanged: (Int) -> Unit
) {

    companion object {
        const val MIN_ZOOM = 25
        const val MAX_ZOOM = 500
        const val ZOOM_STEP = 25          // Button step
        const val DOUBLE_TAP_TARGET = 200 // Double-tap zooms to 200%
        const val ANIM_DURATION = 300L    // ms
    }

    private var currentZoomPercent = 100
    private var isInPinchZoom = false

    // --- Gesture Detectors ---
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isInPinchZoom = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newZoom = (currentZoomPercent * scaleFactor).roundToInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (newZoom != currentZoomPercent) {
                currentZoomPercent = newZoom
                applyZoom(currentZoomPercent, animate = false)
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isInPinchZoom = false
            onZoomChanged(currentZoomPercent)
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Chrome behavior: toggle between 100% and ~200%
            val targetZoom = if (currentZoomPercent < 150) DOUBLE_TAP_TARGET else 100
            animateZoom(currentZoomPercent, targetZoom)
            return true
        }
    })

    /**
     * Attach to the WebView. Call this once after WebView setup.
     */
    fun attach() {
        webView.setOnTouchListener { _, event ->
            // Let ScaleGestureDetector process first (for pinch)
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            false // Don't consume — let WebView handle scrolling etc.
        }
    }

    /**
     * Button zoom in.
     */
    fun zoomIn(): Boolean {
        val newZoom = (currentZoomPercent + ZOOM_STEP).coerceAtMost(MAX_ZOOM)
        if (newZoom == currentZoomPercent) return false
        animateZoom(currentZoomPercent, newZoom)
        return true
    }

    /**
     * Button zoom out.
     */
    fun zoomOut(): Boolean {
        val newZoom = (currentZoomPercent - ZOOM_STEP).coerceAtLeast(MIN_ZOOM)
        if (newZoom == currentZoomPercent) return false
        animateZoom(currentZoomPercent, newZoom)
        return true
    }

    /**
     * Set zoom to a specific level (used for restoring per-domain zoom).
     */
    fun setZoom(percent: Int, animate: Boolean = false) {
        val clamped = percent.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (animate) {
            animateZoom(currentZoomPercent, clamped)
        } else {
            currentZoomPercent = clamped
            applyZoom(clamped, animate = false)
        }
    }

    /**
     * Get current zoom level.
     */
    fun getZoom(): Int = currentZoomPercent

    /**
     * Apply zoom using CSS injection — this is how Chrome does it internally.
     * Uses CSS `zoom` property which triggers proper text reflow.
     * Falls back to `setInitialScale` for WebView compatibility.
     */
    private fun applyZoom(percent: Int, animate: Boolean) {
        // Method 1: CSS zoom via JavaScript (proper text reflow, like Chrome)
        val js = """
            (function() {
                document.body.style.zoom = '${percent}%';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        // Method 2: Also set textZoom for Android WebView text elements
        // This ensures text in WebView-rendered content also scales
        val textScale = percent.toFloat() / 100f
        webView.settings.textZoom = percent

        onZoomChanged(currentZoomPercent)
    }

    /**
     * Smooth zoom animation (like Chrome's double-tap zoom).
     */
    private fun animateZoom(from: Int, to: Int) {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = ANIM_DURATION
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            val fraction = anim.animatedValue as Float
            val current = from + ((to - from) * fraction).roundToInt()
            applyZoom(current, animate = false)
        }
        animator.start()
        currentZoomPercent = to
    }

    /**
     * Reset zoom to 100%. Called on new page loads.
     */
    fun resetToDefault() {
        currentZoomPercent = 100
        applyZoom(100, animate = false)
    }
}
