package com.aiwatch.probe.conversation

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.TextView
import com.aiwatch.probe.theme.CompanionColors
import com.aiwatch.probe.theme.CompanionDimensions
import com.aiwatch.probe.theme.CompanionDrawables

/**
 * A single chat bubble.
 *
 * A TextView rather than a bespoke view group: the visual identity lives entirely in the background
 * shape, the padding and the type, which is what the accepted wrist-chat layouts do. Side is expressed by
 * the caller's gravity; the bubble only mirrors its corner and its colour.
 */
class MessageBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    private var user: Boolean = false

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, CompanionDimensions.bubbleTextSp)
        setLineSpacing(0f, CompanionDimensions.bubbleLineSpacingMultiplier)
        includeFontPadding = false
        val horizontal = CompanionDrawables.dp(context, CompanionDimensions.bubblePaddingHorizontalDp.toFloat()).toInt()
        val vertical = CompanionDrawables.dp(context, CompanionDimensions.bubblePaddingVerticalDp.toFloat()).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
    }

    /** [dotFrame] >= 0 renders the thinking ellipsis instead of text. */
    fun render(item: MessageItem, dotFrame: Int = -1) {
        user = item.isUser
        text = if (dotFrame >= 0) DOT_FRAMES[dotFrame % DOT_FRAMES.size] else item.text
        setTextColor(if (user) CompanionColors.onUserBubble else CompanionColors.primaryText)
        background = CompanionDrawables.bubble(
            context,
            if (user) CompanionColors.userBubble else CompanionColors.assistantBubble,
            user,
        )
    }

    private companion object {
        val DOT_FRAMES = arrayOf("·", "··", "···")
    }
}
