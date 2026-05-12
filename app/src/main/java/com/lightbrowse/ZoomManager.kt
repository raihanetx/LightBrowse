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
 * Professional zoom handler for mobile WebView.
 *
 * HOW CHROME DOES ZOOM ON MOBILE:
 * Chrome doesn't use CSS zoom or setInitialScale. It changes the VIEWPORT WIDTH.
 * When viewport width changes → page's responsive CSS reflows → text wraps, images
 * scale via CSS percentages → no black space, no empty areas. Content fills the screen.
 *
 * This implementation does the same thing:
 * 1. Get the device's default viewport width (e.g. 360px)
 * 2. Calculate new viewport width based on zoom: width = defaultWidth * (100/zoomPercent)
 *    - Zoom 200% → viewport becomes 180px → content thinks it's on a tiny screen → reflows
 *    - Zoom 50% → viewport becomes 720px → content thinks it's on a tablet → reflows
 * 3. Inject viewport meta tag with new width
 * 4. Page CSS does the rest naturally
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
    private var defaultViewportWidth = 0  // Will be detected from the page
    private var isInPinchZoom = false
    var forceZoomEnabled = true

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

    /**
     * Call from WebViewClient.onPageFinished.
     * Detects the page's default viewport width, then applies current zoom.
     */
    fun onPageLoaded() {
        if (forceZoomEnabled) {
            webView.evaluateJavascript(FORCE_ENABLE_ZOOM_JS, null)
        }

        // Detect the page's actual viewport width
        val detectJs = """
            (function() {
                var w = Math.max(document.documentElement.clientWidth, window.innerWidth || 0);
                return w;
            })();
        """.trimIndent()

        webView.evaluateJavascript(detectJs) { result ->
            val width = result?.replace("\"", "")?.toIntOrNull() ?: 0
            if (width > 0) {
                defaultViewportWidth = width
            }
            // Apply zoom after detecting viewport
            applyZoom(currentZoomPercent)
        }
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
     * THE KEY METHOD — viewport-based zoom.
     *
     * How it works:
     * - Zoom 100% → viewport width = device default (e.g. 360px)
     * - Zoom 200% → viewport width = 180px → page thinks screen is smaller → CSS reflows
     * - Zoom 50%  → viewport width = 720px → page thinks screen is bigger → CSS reflows
     *
     * The page's responsive CSS (media queries, % widths, em/rem units) handles everything.
     * No black space, no empty areas. Content fills the screen like Chrome.
     */
    private fun applyZoom(percent: Int) {
        if (defaultViewportWidth <= 0) {
            // Haven't detected viewport yet, use fallback
            defaultViewportWidth = 360
        }

        // Calculate the new viewport width the page should see
        // Higher zoom = smaller viewport = content reflows to be bigger
        val newWidth = (defaultViewportWidth * 100.0 / percent).roundToInt()

        val js = """
            (function() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.setAttribute('content', 'width=$newWidth, initial-scale=1.0, user-scalable=yes');
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)

        // Also adjust textZoom for any text that uses fixed sp/px sizes
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
