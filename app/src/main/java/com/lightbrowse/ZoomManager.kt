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
 * Professional zoom — works like Chrome's accessibility zoom.
 *
 * Uses CSS transform: scale() on the entire page.
 * This magnifies EVERYTHING uniformly — text, images, buttons, layout.
 * No reflow, no black space, no empty areas.
 * Just like looking through a magnifying glass.
 */
class ZoomManager(
    private val context: Context,
    private val webView: WebView,
    private val onZoomChanged: (Int) -> Unit
) {

    companion object {
        const val MIN_ZOOM = 50
        const val MAX_ZOOM = 300
        const val ZOOM_STEP = 25
        const val DOUBLE_TAP_TARGET = 200
        const val ANIM_DURATION = 250L

        private const val FORCE_ENABLE_ZOOM_JS = """
        (function() {
            var meta = document.querySelector('meta[name="viewport"]');
            if (meta) {
                var content = meta.getAttribute('content') || '';
                content = content.replace(/user-scalable\s*=\s*no/gi, 'user-scalable=yes');
                content = content.replace(/user-scalable\s*=\s*0/gi, 'user-scalable=yes');
                content = content.replace(/maximum-scale\s*=\s*1(\.0+)?\s*;?\s*/gi, '');
                content = content.replace(/minimum-scale\s*=\s*1(\.0+)?\s*;?\s*/gi, '');
                if (!/user-scalable/i.test(content)) {
                    content += ', user-scalable=yes';
                }
                meta.setAttribute('content', content);
            } else {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                meta.content = 'width=device-width, initial-scale=1.0, user-scalable=yes';
                document.head.appendChild(meta);
            }
            var style = document.createElement('style');
            style.textContent = '*, html, body { touch-action: manipulation !important; }';
            document.head.appendChild(style);
        })();
        """
    }

    private var currentZoomPercent = 100
    private var isInPinchZoom = false
    var forceZoomEnabled = true

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
                applyZoom(currentZoomPercent)
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
            val targetZoom = if (currentZoomPercent < 150) DOUBLE_TAP_TARGET else 100
            animateZoom(currentZoomPercent, targetZoom)
            return true
        }
    })

    fun attach() {
        webView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    fun onPageLoaded() {
        if (forceZoomEnabled) {
            webView.evaluateJavascript(FORCE_ENABLE_ZOOM_JS, null)
        }
        // Apply current zoom to new page
        applyZoom(currentZoomPercent)
    }

    fun zoomIn(): Boolean {
        val newZoom = (currentZoomPercent + ZOOM_STEP).coerceAtMost(MAX_ZOOM)
        if (newZoom == currentZoomPercent) return false
        animateZoom(currentZoomPercent, newZoom)
        return true
    }

    fun zoomOut(): Boolean {
        val newZoom = (currentZoomPercent - ZOOM_STEP).coerceAtLeast(MIN_ZOOM)
        if (newZoom == currentZoomPercent) return false
        animateZoom(currentZoomPercent, newZoom)
        return true
    }

    fun setZoom(percent: Int, animate: Boolean = false) {
        val clamped = percent.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (animate) {
            animateZoom(currentZoomPercent, clamped)
        } else {
            currentZoomPercent = clamped
            applyZoom(clamped)
        }
    }

    fun getZoom(): Int = currentZoomPercent

    /**
     * Apply zoom using CSS transform: scale().
     *
     * This is how Chrome actually zooms on mobile:
     * - transform: scale() magnifies EVERYTHING uniformly
     * - transform-origin: 0 0 scales from top-left
     * - The body is made wider to create a scrollable area
     * - Page scrolls normally to see the zoomed content
     *
     * At zoom 200%, scale = 2.0 → everything looks 2x bigger
     * At zoom 50%, scale = 0.5 → everything looks half size
     */
    private fun applyZoom(percent: Int) {
        val scale = percent / 100.0

        val js = """
        (function() {
            // Ensure the zoom style element exists
            var styleId = '__lightbrowse_zoom__';
            var style = document.getElementById(styleId);
            if (!style) {
                style = document.createElement('style');
                style.id = styleId;
                document.head.appendChild(style);
            }

            // Set the zoom via CSS transform
            style.textContent = 'html { ' +
                'transform: scale($scale); ' +
                'transform-origin: 0 0; ' +
                '-webkit-transform: scale($scale); ' +
                '-webkit-transform-origin: 0 0; ' +
                'width: ' + (100 / $scale) + '%; ' +
                'min-height: ' + (100 / $scale) + 'vh; ' +
            '}';
        })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
        onZoomChanged(currentZoomPercent)
    }

    private fun animateZoom(from: Int, to: Int) {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = ANIM_DURATION
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            val fraction = anim.animatedValue as Float
            val current = from + ((to - from) * fraction).roundToInt()
            applyZoom(current)
        }
        animator.start()
        currentZoomPercent = to
    }

    fun resetToDefault() {
        currentZoomPercent = 100
        applyZoom(100)
    }
}
