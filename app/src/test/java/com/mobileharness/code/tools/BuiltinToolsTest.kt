package com.mobileharness.code.tools

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinToolsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun catalogHasUniqueNamespacedNames() {
        val catalog = BuiltinTools.catalog()
        assertEquals(3, catalog.size)
        val names = catalog.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("builtin_") })
    }

    @Test
    fun isBuiltinOnlyMatchesCatalog() {
        assertTrue(BuiltinTools.isBuiltin(BuiltinTools.SEARCH_FILES))
        assertTrue(BuiltinTools.isBuiltin(BuiltinTools.READ_FILE))
        assertTrue(BuiltinTools.isBuiltin(BuiltinTools.LIST_STRUCTURE))
        assertFalse(BuiltinTools.isBuiltin("search_files"))
        assertFalse(BuiltinTools.isBuiltin("delete_file"))
    }

    @Test
    fun searchExecutesAndFindsContent() {
        val root = tmp.root
        File(root, "Main.kt").apply { parentFile.mkdirs() }.writeText("fun hello() { println(\"HelloWorld\") }")
        val result = BuiltinTools.execute(
            BuiltinTools.SEARCH_FILES,
            root,
            JSONObject().put("query", "helloworld"),
            ecoEnabled = false,
        )
        assertTrue(result is BuiltinTools.Result.Success)
        assertTrue((result as BuiltinTools.Result.Success).text.contains("Main.kt"))
    }

    @Test
    fun readExecutesAndDeniesUnsafePath() {
        val root = tmp.root
        File(root, "notes.txt").writeText("hello")
        val ok = BuiltinTools.execute(BuiltinTools.READ_FILE, root, JSONObject().put("path", "notes.txt"), false)
        assertEquals("hello", (ok as BuiltinTools.Result.Success).text)

        val denied = BuiltinTools.execute(BuiltinTools.READ_FILE, root, JSONObject().put("path", "../secret"), false)
        assertTrue((denied as BuiltinTools.Result.Success).text.startsWith("Denied:"))
    }

    @Test
    fun listStructureExecutes() {
        val root = tmp.root
        File(root, "README.md").writeText("# hi")
        val result = BuiltinTools.execute(BuiltinTools.LIST_STRUCTURE, root, JSONObject(), false)
        assertTrue((result as BuiltinTools.Result.Success).text.contains("README.md"))
    }

    @Test
    fun unknownToolFailsClosed() {
        val result = BuiltinTools.execute("builtin_delete_everything", tmp.root, JSONObject(), false)
        assertTrue(result is BuiltinTools.Result.Failure)
    }
}
