package com.mobileharness.code.telegram

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Process-wide outbox for async notifications (backup finished, build done, …) that the
 * Telegram service broadcasts to every paired chat. `tryEmit` with a small buffer means
 * producing a message never blocks the caller; when the buffer is full the oldest drops.
 */
object TelegramNotifications {
    private val mutableOutbox = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val outbox: SharedFlow<String> = mutableOutbox

    fun publish(message: String) {
        mutableOutbox.tryEmit(message)
    }
}
