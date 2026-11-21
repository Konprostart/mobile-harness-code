package com.mobileharness.code.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStoreTest {

    private fun store() = PermissionStore()

    @Test
    fun `upsert and snapshot round trip`() {
        val s = store()
        s.upsert(PermissionRule("a", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_SESSION, projectId = "p1"))
        assertEquals(1, s.snapshot().size)
        assertEquals("Bash", s.snapshot().first().subjectId)
    }

    @Test
    fun `consume allow once removes the rule`() {
        val s = store()
        s.upsert(PermissionRule("a", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_ONCE))
        assertTrue(s.consumeAllowOnce("a"))
        assertTrue(s.snapshot().isEmpty())
        assertFalse(s.consumeAllowOnce("a"))
    }

    @Test
    fun `clear session scoped removes only session grants`() {
        val s = store()
        s.upsert(PermissionRule("s", PermissionSubject.TOOL, "Bash", PermissionLevel.ALLOW_SESSION))
        s.upsert(PermissionRule("p", PermissionSubject.TOOL, "Edit", PermissionLevel.ALWAYS_ALLOW))
        s.clearSessionScoped()
        assertEquals(listOf("p"), s.snapshot().map { it.id })
    }

    @Test
    fun `json round trip preserves rules`() {
        val s = store()
        s.upsert(PermissionRule("a", PermissionSubject.MCP_SERVER, "srv", PermissionLevel.ALWAYS_ALLOW, workspacePath = "/ws"))
        s.upsert(PermissionRule("b", PermissionSubject.TOOL, "*", PermissionLevel.DENY))
        val json = PermissionRuleCodec.encode(s.snapshot())
        val decoded = PermissionRuleCodec.decode(json)
        assertEquals(2, decoded.size)
        assertEquals(PermissionLevel.ALWAYS_ALLOW, decoded.first { it.id == "a" }.level)
        assertEquals("*", decoded.first { it.id == "b" }.subjectId)
    }

    @Test
    fun `decode ignores malformed input`() {
        assertEquals(emptyList<PermissionRule>(), PermissionRuleCodec.decode("not json"))
        assertEquals(emptyList<PermissionRule>(), PermissionRuleCodec.decode(null))
        assertEquals(emptyList<PermissionRule>(), PermissionRuleCodec.decode("[{\"id\":\"x\"}]"))
    }
}
