package com.aiwatch.probe.theme

/**
 * Geometry for the 410x502 target. All values are dp (text is sp) so the layout survives a density
 * change on a different watch panel without touching the code.
 */
object CompanionDimensions {
    const val targetWidthDp = 410
    const val targetHeightDp = 502

    const val edgeMarginDp = 18
    const val topBarHeightDp = 52

    /** Character stage. Tall enough to be the visual centre, short enough to leave room for chat + PTT. */
    const val stageHeightDp = 168
    const val stageRadiusDp = 28

    const val bubbleMaxWidthFraction = 0.78f
    const val bubblePaddingHorizontalDp = 12
    const val bubblePaddingVerticalDp = 9
    const val bubbleCornerLargeDp = 16
    const val bubbleCornerSmallDp = 4
    const val bubbleTextSp = 13f
    const val bubbleLineSpacingMultiplier = 1.15f

    const val pttHeightDp = 56
    const val pttRadiusDp = 28
    const val pttTextSp = 14f
    const val pttPressedScale = 0.97f

    /** Home shows only the tail of the transcript; older messages scroll. */
    const val visibleMessageCount = 6
}
