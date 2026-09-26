package com.aiwatch.probe.conversation

import com.aiwatch.probe.theme.CompanionColors

/**
 * The companion's four visible states. P0-1 drives them from a scripted controller only; P0-2 will drive
 * the same enum from the real voice loop, so nothing in the UI has to change when that lands.
 */
enum class ConversationState { IDLE, LISTENING, THINKING, SPEAKING }

/** Line shown under the character name in the top bar. */
fun ConversationState.statusLabel(characterName: String): String = when (this) {
    ConversationState.IDLE -> "在这里陪着你"
    ConversationState.LISTENING -> "正在听你说"
    // Braces are required: CJK characters are valid Kotlin identifier characters, so "$characterName正在想"
    // would parse as one identifier rather than an interpolation followed by literal text.
    ConversationState.THINKING -> "${characterName}正在想…"
    ConversationState.SPEAKING -> "正在和你说话"
}

/** Label on the push-to-talk capsule. */
fun ConversationState.pushToTalkLabel(characterName: String): String = when (this) {
    ConversationState.IDLE -> "按住和我说话"
    ConversationState.LISTENING -> "正在听你说…"
    ConversationState.THINKING -> "${characterName}在想"
    ConversationState.SPEAKING -> "${characterName}正在说话"
}

fun ConversationState.accentColor(): Int = when (this) {
    ConversationState.IDLE -> CompanionColors.companion
    ConversationState.LISTENING -> CompanionColors.listening
    ConversationState.THINKING -> CompanionColors.thinking
    ConversationState.SPEAKING -> CompanionColors.speaking
}

/** Whether the capsule should read as armed (i.e. the user is doing something). */
fun ConversationState.isBusy(): Boolean = this != ConversationState.IDLE
