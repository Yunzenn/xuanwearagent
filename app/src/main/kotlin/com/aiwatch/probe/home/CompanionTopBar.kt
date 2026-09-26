package com.aiwatch.probe.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.aiwatch.probe.conversation.ConversationState
import com.aiwatch.probe.conversation.accentColor
import com.aiwatch.probe.conversation.statusLabel
import com.aiwatch.probe.theme.CompanionColors
import com.aiwatch.probe.theme.CompanionDimensions
import com.aiwatch.probe.theme.CompanionDrawables

/**
 * Home header: who the companion is on the left, settings on the right.
 *
 * Nothing technical belongs here. Connection state, endpoints, debug toggles and avatar-engine status live
 * in Settings, because this row is the first thing the user sees and must read as a character, not a probe.
 */
class CompanionTopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onSettingsClick: (() -> Unit)? = null

    private val name = TextView(context).apply {
        setTextColor(CompanionColors.primaryText)
        textSize = 13f
        letterSpacing = 0.04f
    }

    private val statusDot = View(context)

    private val status = TextView(context).apply {
        setTextColor(CompanionColors.secondaryText)
        textSize = 10f
    }

    private val settings = DotsButton(context).apply {
        contentDescription = "设置"
        setOnClickListener { onSettingsClick?.invoke() }
    }

    init {
        val margin = CompanionDrawables.dp(context, CompanionDimensions.edgeMarginDp.toFloat()).toInt()
        val dotSize = CompanionDrawables.dp(context, 6f).toInt()

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dotSize, dotSize).apply {
                rightMargin = CompanionDrawables.dp(context, 6f).toInt()
            })
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(statusRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = CompanionDrawables.dp(context, 3f).toInt() })
        }

        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = margin
        })

        val buttonSize = CompanionDrawables.dp(context, 32f).toInt()
        addView(settings, LayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = margin - CompanionDrawables.dp(context, 8f).toInt()
        })
    }

    fun render(characterName: String, state: ConversationState) {
        name.text = characterName
        status.text = state.statusLabel(characterName)
        val accent = state.accentColor()
        status.setTextColor(accent)
        statusDot.background = CompanionDrawables.rounded(context, accent, 4f).apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
        }
    }

    /** Overflow affordance for Settings: three dots, no icon asset and no third-party icon font. */
    private class DotsButton(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CompanionColors.secondaryText }

        override fun onDraw(canvas: Canvas) {
            val radius = CompanionDrawables.dp(context, 1.9f)
            val gap = CompanionDrawables.dp(context, 4.6f)
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx - gap, cy, radius, paint)
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawCircle(cx + gap, cy, radius, paint)
        }
    }
}
