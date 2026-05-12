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
 * Professional zoom — viewport + CSS zoom (Chrome's actual approach).
 *
 * How Chrome zoom works on mobile:
 * 1. Lock viewport width to device width (e.g. 360px)
 * 2. Apply CSS zoom factor on html element
 * 3. Browser layout engine treats viewport as (360/zoom)px wide
 * 4. At 200%: page thinks viewport is 180px → CSS reflows → text wraps bigger
 * 5. Everything is magnified 2x AND fills the screen — no blank space
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
     * Apply zoom using viewport width lock + CSS zoom.
     *
     * This is Chrome's actual zoom mechanism:
     * - Viewport meta sets width=device-width (locks to screen width)
     * - CSS zoom on html element scales everything
     * - Layout engine reflows content at the zoomed scale
     * - No blank space — content fills the screen naturally
     */
    private fun applyZoom(percent: Int) {
        val scale = percent / 100.0

        val js = """
        (function() {
            // Step 1: Lock viewport to device width
            var meta = document.querySelector('meta[name="viewport"]');
            if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                document.head.appendChild(meta);
            }
            meta.setAttribute('content', 'width=device-width, initial-scale=1.0, user-scalable=yes');

            // Step 2: Apply CSS zoom on html element
            // This makes the browser layout engine treat the viewport as smaller,
            // causing all responsive CSS to reflow at the zoomed scale.
            // Everything gets magnified AND fills the screen.
            var html = document.documentElement;
            html.style.zoom = '$scale';
            html.style.width = '100%';
            html.style.maxWidth = '100%';

            // Step 3: Ensure body fills the zoomed viewport
            if (document.body) {
                document.body.style.width = '100%';
                document.body.style.maxWidth = '100%';
                document.body.style.margin = '0';
                document.body.style.padding = '0';
            }
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
