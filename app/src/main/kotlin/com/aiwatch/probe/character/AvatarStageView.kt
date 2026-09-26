package com.aiwatch.probe.character

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.aiwatch.probe.conversation.ConversationState
import com.aiwatch.probe.theme.CompanionColors
import com.aiwatch.probe.theme.CompanionDimensions
import com.aiwatch.probe.theme.CompanionDrawables

/**
 * The character stage: the visual centre of Home.
 *
 * Deliberately NOT an avatar button. The art is fitted, never circle-cropped, and sits on a soft glow
 * plate so a transparent or portrait-cut still still reads as a character rather than a sticker.
 *
 * State feedback is intentionally restrained: a slow breathing scale when idle and a slightly stronger
 * glow otherwise. No waveform, no particle system, no animation library.
 */
class AvatarStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val art = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
    }

    /** Drawn behind [art]; also the fallback when no asset is available. */
    private val plate = StagePlateView(context)

    private val placeholderLabel = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(CompanionColors.secondaryText)
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        letterSpacing = 0.12f
    }

    private var breathing: ObjectAnimator? = null
    private var state: ConversationState = ConversationState.IDLE

    init {
        addView(plate, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(placeholderLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
    }

    fun bind(provider: AvatarProvider, label: String) {
        placeholderLabel.text = label
        val drawable = provider.stageDrawable(context)
        art.setImageDrawable(drawable)
        // Only show the label when there is no art, so the stage never reads as a broken image.
        placeholderLabel.visibility = if (drawable == null) VISIBLE else GONE
        art.visibility = if (drawable == null) INVISIBLE else VISIBLE
    }

    fun render(state: ConversationState) {
        this.state = state
        plate.setState(state)
        when (state) {
            ConversationState.IDLE -> startBreathing()
            else -> stopBreathing()
        }
    }

    private fun startBreathing() {
        if (breathing != null) return
        breathing = ObjectAnimator.ofPropertyValuesHolder(
            art,
            PropertyValuesHolder.ofFloat(SCALE_X, 1.0f, 1.015f),
            PropertyValuesHolder.ofFloat(SCALE_Y, 1.0f, 1.015f),
        ).apply {
            duration = BREATHE_PERIOD_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopBreathing() {
        breathing?.cancel()
        breathing = null
        art.scaleX = 1f
        art.scaleY = 1f
    }

    override fun onDetachedFromWindow() {
        stopBreathing()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val BREATHE_PERIOD_MS = 3000L
    }

    /**
     * Soft glow plate. Drawn rather than imaged so the stage needs no art assets of its own: an inset
     * rounded rectangle with a low-alpha companion tint, brightened a little while the companion speaks.
     */
    private class StagePlateView(context: Context) : android.view.View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val radius = CompanionDrawables.dp(context, CompanionDimensions.stageRadiusDp)
        private val inset = CompanionDrawables.dp(context, 6f)
        private var tint = CompanionColors.stageGlow

        fun setState(state: ConversationState) {
            val base = when (state) {
                ConversationState.IDLE -> Color.argb(31, 202, 232, 179)
                ConversationState.LISTENING -> Color.argb(41, 158, 216, 255)
                ConversationState.THINKING -> Color.argb(41, 232, 216, 148)
                ConversationState.SPEAKING -> Color.argb(59, 202, 232, 179)
            }
            if (base != tint) {
                tint = base
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = tint
            val r = android.graphics.RectF(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(r, radius, radius, paint)
        }
    }
}
