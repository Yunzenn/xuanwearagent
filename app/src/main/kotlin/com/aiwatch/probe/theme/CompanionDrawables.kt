package com.aiwatch.probe.theme

import android.content.Context
import android.graphics.drawable.GradientDrawable

/**
 * Shape helpers for Native Views. No Compose, no shape/animation library, no Material runtime: the P0-1
 * brief forbids new UI stacks, so bubbles and pills are plain [GradientDrawable]s.
 *
 * Bubble corner asymmetry follows the accepted wrist-chat convention: the corner nearest the speaker's
 * side is tight, the one pointing at the transcript is generous.
 */
object CompanionDrawables {

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    /** Overload so the dp-suffixed Int constants in [CompanionDimensions] can be passed directly. */
    fun dp(context: Context, value: Int): Float =
        value * context.resources.displayMetrics.density

    fun rounded(context: Context, fillColor: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp)
            setColor(fillColor)
        }

    fun stroked(context: Context, fillColor: Int, strokeColor: Int, radiusDp: Float): GradientDrawable =
        rounded(context, fillColor, radiusDp).apply {
            setStroke(dp(context, 1f).toInt().coerceAtLeast(1), strokeColor)
        }

    /** Message bubble. [isUser] tucks the top-end corner instead of the top-start one. */
    fun bubble(context: Context, fillColor: Int, isUser: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val large = dp(context, CompanionDimensions.bubbleCornerLargeDp)
            val small = dp(context, CompanionDimensions.bubbleCornerSmallDp)
            cornerRadii = if (isUser) {
                floatArrayOf(large, large, small, small, large, large, large, large)
            } else {
                floatArrayOf(small, small, large, large, large, large, large, large)
            }
            setColor(fillColor)
        }
}
