package com.lightbrowse

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Floating zoom indicator — shows current zoom % with a smooth fade.
 * Appears when zoom changes, fades out after 1.5s.
 * Positioned like Chrome's zoom indicator (bottom-center).
 */
class ZoomIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC333333") // Dark semi-transparent
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val cornerRadius = 24f
    private var currentText = "100%"
    private var alphaFloat = 0f
    private var hideAnimator: ValueAnimator? = null

    init {
        visibility = INVISIBLE
        alpha = 0f
    }

    /**
     * Show the indicator with the given zoom percentage.
     */
    fun showZoomLevel(percent: Int) {
        currentText = "${percent}%"

        // Cancel any pending hide
        hideAnimator?.cancel()

        // Show immediately
        visibility = VISIBLE
        animate()
            .alpha(1f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Auto-hide after 1.5 seconds
        hideAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = 1500
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { alphaFloat = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = INVISIBLE
                }
            })
            start()
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE) return

        val w = width.toFloat()
        val h = height.toFloat()
        val rect = RectF(0f, 0f, w, h)

        // Background pill
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        // Text centered
        val textX = w / 2f
        val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(currentText, textX, textY, textPaint)
    }
}
