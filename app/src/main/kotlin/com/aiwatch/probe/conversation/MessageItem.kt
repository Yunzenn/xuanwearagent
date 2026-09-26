package com.aiwatch.probe.conversation

enum class MessageAuthor { USER, COMPANION }

/**
 * One transcript entry. [pending] marks the companion bubble that is still "thinking", which the bubble
 * renders as an animated ellipsis instead of text.
 */
data class MessageItem(
    val id: Long,
    val author: MessageAuthor,
    val text: String,
    val timestampMillis: Long,
    val pending: Boolean = false,
) {
    val isUser: Boolean get() = author == MessageAuthor.USER
}
