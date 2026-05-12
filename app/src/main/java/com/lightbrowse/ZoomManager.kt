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
 * Implements Chrome's accessibility zoom features:
 * - Pinch-to-zoom (multi-touch gesture)
 * - Double-tap zoom (toggle 100% ↔ 200%)
 * - Button zoom (+/− with 25% steps)
 * - Force Enable Zoom (override websites that block zoom)
 * - Text reflow via CSS zoom + textZoom
 * - Smooth animations
 */
class ZoomManager(
    private val context: Context,
    private val webView: WebView,
    private val onZoomChanged: (Int) -> Unit
) {

    companion object {
        const val MIN_ZOOM = 25
        const val MAX_ZOOM = 500
        const val ZOOM_STEP = 25
        const val DOUBLE_TAP_TARGET = 200
        const val ANIM_DURATION = 300L

        /**
         * JavaScript to force-enable zoom on any website.
         * This overrides the viewport meta tag restrictions that sites use to block zoom.
         * Equivalent to Chrome's "Force Enable Zoom" accessibility setting.
         *
         * What it does:
         * 1. Removes user-scalable=no
         * 2. Removes restrictive maximum-scale / minimum-scale
         * 3. Enables pinch-to-zoom on sites that disabled it
         * 4. Preserves existing viewport width/initial-scale settings
         */
        private const val FORCE_ENABLE_ZOOM_JS = """
        (function() {
            // --- Fix viewport meta tag ---
            var meta = document.querySelector('meta[name="viewport"]');
            if (meta) {
                var content = meta.getAttribute('content') || '';
                // Remove zoom-blocking directives
                content = content.replace(/user-scalable\s*=\s*no/gi, 'user-scalable=yes');
                content = content.replace(/user-scalable\s*=\s*0/gi, 'user-scalable=yes');
                content = content.replace(/maximum-scale\s*=\s*1(\.0+)?\s*;?\s*/gi, '');
                content = content.replace(/minimum-scale\s*=\s*1(\.0+)?\s*;?\s*/gi, '');
                // If no user-scalable was present, add it
                if (!/user-scalable/i.test(content)) {
                    content += ', user-scalable=yes';
                }
                meta.setAttribute('content', content);
            } else {
                // No viewport meta at all — create one that allows zoom
                meta = document.createElement('meta');
                meta.name = 'viewport';
                meta.content = 'width=device-width, initial-scale=1.0, user-scalable=yes';
                document.head.appendChild(meta);
            }

            // --- Fix touch-action CSS (some sites use CSS to block pinch) ---
            var style = document.createElement('style');
            style.textContent = '*, html, body { touch-action: manipulation !important; }';
            document.head.appendChild(style);

            // --- Override JavaScript zoom blockers ---
            // Some sites add event listeners that prevent pinch-zoom
            // by calling preventDefault on touchmove/touchstart
            var originalAddEventListener = EventTarget.prototype.addEventListener;
            EventTarget.prototype.addEventListener = function(type, listener, options) {
                if (type === 'touchmove' || type === 'gesturestart' || type === 'gesturechange') {
                    // Don't block zoom-related touch events on the document
                    if (this === document || this === document.body || this === window) {
                        var wrappedListener = function(e) {
                            // Allow multi-touch (pinch) through
                            if (e.touches && e.touches.length > 1) return;
                            if (e.scale && e.scale !== 1) return;
                            listener.call(this, e);
                        };
                        return originalAddEventListener.call(this, type, wrappedListener, options);
                    }
                }
                return originalAddEventListener.call(this, type, listener, options);
            };
        })();
        """
    }

    private var currentZoomPercent = 100
    private var isInPinchZoom = false
    var forceZoomEnabled = true  // Default ON, like Chrome accessibility

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
            val targetZoom = if (currentZoomPercent < 150) DOUBLE_TAP_TARGET else 100
            animateZoom(currentZoomPercent, targetZoom)
            return true
        }
    })

    /**
     * Attach to the WebView. Call once after setup.
     */
    fun attach() {
        webView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    /**
     * Call this from WebViewClient.onPageFinished to force-enable zoom on every page.
     * This is the "Force Enable Zoom" feature — overrides sites that block zoom.
     */
    fun onPageLoaded() {
        if (forceZoomEnabled) {
            webView.evaluateJavascript(FORCE_ENABLE_ZOOM_JS, null)
        }
        // Re-apply current zoom to new page
        applyZoom(currentZoomPercent, animate = false)
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
     * Set zoom to a specific level.
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

    fun getZoom(): Int = currentZoomPercent

    /**
     * Apply zoom — CSS zoom for reflow + textZoom for WebView text.
     */
    private fun applyZoom(percent: Int, animate: Boolean) {
        // CSS zoom: proper text reflow (how Chrome does it)
        val js = """
            (function() {
                if (document.body) {
                    document.body.style.zoom = '${percent}%';
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        // textZoom: scales text in WebView-rendered HTML
        webView.settings.textZoom = percent

        onZoomChanged(currentZoomPercent)
    }

    /**
     * Smooth zoom animation.
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

    fun resetToDefault() {
        currentZoomPercent = 100
        applyZoom(100, animate = false)
    }
}
