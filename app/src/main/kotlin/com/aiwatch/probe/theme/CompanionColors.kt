package com.aiwatch.probe.theme

import android.graphics.Color

/**
 * P0-1 palette: OLED-dark base with a soft mint / powder-blue companion accent.
 *
 * Deliberately excluded, per the P0-1 brief: WeChat green, high-saturation pink, neon RGB, heavy
 * glassmorphism, and Material default purple.
 */
object CompanionColors {
    val background = Color.parseColor("#0D1110")
    val surface = Color.parseColor("#171D1A")
    val surfaceElevated = Color.parseColor("#202824")
    val primaryText = Color.parseColor("#F6F2EB")
    val secondaryText = Color.parseColor("#AEB9B2")

    /** Accent used for the companion's own presence. */
    val companion = Color.parseColor("#CAE8B3")
    val listening = Color.parseColor("#9ED8FF")
    val thinking = Color.parseColor("#E8D894")
    val speaking = Color.parseColor("#CAE8B3")

    val userBubble = Color.parseColor("#CBE7B6")
    val assistantBubble = Color.parseColor("#202824")

    /** Text on top of [userBubble]; the mint fill is light, so the label must be dark. */
    val onUserBubble = Color.parseColor("#16210F")

    val hairline = Color.parseColor("#2A342F")

    /** Very low alpha glow behind the character stage; the stage must stay the visual centre. */
    val stageGlow = Color.parseColor("#1FCAE8B3")
}
