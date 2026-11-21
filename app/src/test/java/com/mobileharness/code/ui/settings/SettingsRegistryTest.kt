package com.mobileharness.code.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRegistryTest {

    @Test
    fun `register and list sorted by ordinal`() {
        SettingsRegistry.clear()
        SettingsRegistry.register(SettingsRegistry.SectionSpec("b", "Beta", ordinal = 20))
        SettingsRegistry.register(SettingsRegistry.SectionSpec("a", "Alpha", ordinal = 10))
        val list = SettingsRegistry.list()
        assertEquals(listOf("a", "b"), list.map { it.id })
    }

    @Test
    fun `register replaces same id`() {
        SettingsRegistry.clear()
        SettingsRegistry.register(SettingsRegistry.SectionSpec("x", "Old", ordinal = 1))
        SettingsRegistry.register(SettingsRegistry.SectionSpec("x", "New", ordinal = 2))
        assertEquals(1, SettingsRegistry.list().size)
        assertEquals("New", SettingsRegistry.get("x")!!.title)
    }

    @Test
    fun `unregister removes section`() {
        SettingsRegistry.clear()
        SettingsRegistry.register(SettingsRegistry.SectionSpec("x", "X"))
        SettingsRegistry.unregister("x")
        assertNull(SettingsRegistry.get("x"))
    }

    @Test
    fun `get returns registered spec`() {
        SettingsRegistry.clear()
        SettingsRegistry.register(SettingsRegistry.SectionSpec("v", "Voice", ordinal = 5))
        assertNotNull(SettingsRegistry.get("v"))
        assertTrue(SettingsRegistry.list().isNotEmpty())
    }
}
