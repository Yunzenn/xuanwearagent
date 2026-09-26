package com.aiwatch.probe.theme

/**
 * Geometry for the real target panel.
 *
 * CD12Max: 2.06" AMOLED, 410x502 px. Diagonal 648 px over 2.06" is ~315 dpi, which Android buckets to
 * **320 dpi / density 2.0**, so the usable canvas is only **205 x 251 dp** — not 410 x 502 dp.
 *
 * This file was originally written against a 410x502 dp assumption and the layout overflowed the panel
 * at the real density (the transcript and the push-to-talk capsule were pushed off-screen). Every value
 * below is derived from 205x251 dp. All sizes live here on purpose so that a density correction is a
 * one-file change.
 *
 * Vertical budget at 251 dp:
 *   top bar 38 + stage 84 + transcript gap 6 + [transcript, weight 1] + ptt gap 6 + ptt 44 + bottom 10
 *   = 188 dp of fixed content, leaving ~63 dp for the transcript tail.
 */
object CompanionDimensions {
    const val targetWidthDp = 205
    const val targetHeightDp = 251
    const val targetDensityDpi = 320

    const val edgeMarginDp = 10
    const val topBarHeightDp = 38

    /** Character stage: a third of the panel, so it stays the visual centre without crowding the chat. */
    const val stageHeightDp = 84
    const val stageRadiusDp = 16

    const val bubbleMaxWidthFraction = 0.78f
    const val bubblePaddingHorizontalDp = 9
    const val bubblePaddingVerticalDp = 6
    const val bubbleCornerLargeDp = 12
    const val bubbleCornerSmallDp = 3
    const val bubbleTextSp = 11f
    const val bubbleLineSpacingMultiplier = 1.12f

    const val pttHeightDp = 44
    const val pttRadiusDp = 22
    const val pttTextSp = 12f
    const val pttPressedScale = 0.97f

    /** Home shows only the tail of the transcript; older messages scroll. */
    const val visibleMessageCount = 4
}
