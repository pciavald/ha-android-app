package io.homeassistant.companion.android.conversation.views

import java.util.concurrent.atomic.AtomicLong

private const val PLACEHOLDER = "…"

/**
 * A message of the Assist conversation.
 *
 * @param id identity of the message: [copy] keeps it, so a message can be found again once its
 * content changed, and two messages with the same content (like two placeholders) stay distinct
 */
data class AssistMessage(
    val message: String,
    val isInput: Boolean,
    val isError: Boolean = false,
    val id: Long = nextId.incrementAndGet(),
) {
    val isPlaceholder: Boolean
        get() = message == PLACEHOLDER

    companion object {
        private val nextId = AtomicLong()

        fun placeholder(isInput: Boolean): AssistMessage = AssistMessage(PLACEHOLDER, isInput)
    }
}
