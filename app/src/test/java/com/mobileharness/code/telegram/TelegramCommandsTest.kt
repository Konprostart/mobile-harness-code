package com.mobileharness.code.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramCommandsTest {
    @Test
    fun parsesPlainCommand() {
        val command = TelegramCommand.parse("/status")
        assertEquals("status", command?.name)
        assertEquals(emptyList<String>(), command?.args)
    }

    @Test
    fun stripsBotMention() {
        val command = TelegramCommand.parse("/status@MyBot  extra")
        assertEquals("status", command?.name)
        assertEquals(listOf("extra"), command?.args)
    }

    @Test
    fun capsArgsAndLength() {
        val long = "a".repeat(500)
        val command = TelegramCommand.parse("/backup $long one two three four")
        assertEquals("backup", command?.name)
        assertEquals(3, command?.args?.size)
        assertEquals(200, command?.args?.first()?.length)
    }

    @Test
    fun ignoresNonCommands() {
        assertNull(TelegramCommand.parse("hello"))
        assertNull(TelegramCommand.parse(null))
        assertNull(TelegramCommand.parse(""))
        assertNull(TelegramCommand.parse("  /  "))
    }

    @Test
    fun tokenFormatAcceptsRealShape() {
        assertTrue(TELEGRAM_TOKEN_FORMAT.matches("1234567890:AAH-abc123_DEFghijklmnopqrstuvwxyz0123456789"))
        assertFalse(TELEGRAM_TOKEN_FORMAT.matches("1234567890"))
        assertFalse(TELEGRAM_TOKEN_FORMAT.matches("1234:AAH-abc123_DEFghijklmnopqrstuvwxyz0123456789"))
        assertFalse(TELEGRAM_TOKEN_FORMAT.matches("1234567890:short"))
    }

    @Test
    fun keyboardDropsInvalidButtonsAndSerializes() {
        val keyboard = TelegramKeyboard(
            buttons = listOf(
                listOf(TelegramButton("Build", "bd"), TelegramButton("", "empty")),
                listOf(TelegramButton("Docs", "https://example.com")),
            ),
        )
        val json = keyboard.toJson()
        assertTrue(json!!.contains("\"inline_keyboard\""))
        assertTrue(json.contains("\"text\":\"Build\""))
        assertFalse(json.contains("empty"))
    }

    @Test
    fun emptyKeyboardSerializesToNull() {
        assertNull(TelegramKeyboard(emptyList()).toJson())
        assertNull((null as TelegramKeyboard?).toJson())
    }

    @Test
    fun buttonValidityRejectsOverlongCallbacks() {
        assertTrue(TelegramButton("ok", "short").valid)
        assertFalse(TelegramButton("ok", "x".repeat(65)).valid)
        assertFalse(TelegramButton("", "data").valid)
        assertTrue(TelegramButton("link", "https://example.com/a/very/long/url".repeat(4)).valid)
    }
}
