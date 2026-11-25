package com.mobileharness.code.ui

import com.mobileharness.code.model.ChatMessage

/**
 * Clipboard payload selection for chat messages.
 *
 * Both the main workspace chat and the Sidekick assistant resolve what a message copies to
 * the clipboard through these helpers, so that copying a message copies *only* its body
 * text — never the author, timestamps, attachments or any other UI chrome.
 */
object MessageCopy {

    /** Body text copied for a main-workspace user message (raw body, no metadata). */
    fun mainUser(message: ChatMessage): String = message.text

    /** Body text copied for a main-workspace assistant message (the rendered body). */
    fun mainAssistant(renderedText: String): String = renderedText

    /** Body text copied for a Sidekick message (reasoning is deliberately excluded). */
    fun sidekick(bodyText: String): String = bodyText
}
