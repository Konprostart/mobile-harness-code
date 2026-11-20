package com.mobileharness.code.tools

import com.mobileharness.code.mcp.McpTool
import org.json.JSONObject
import java.io.File

/**
 * The assistant's built-in, on-device workspace tools. Unlike MCP tools (which reach a remote or
 * local process), these run in-process against the app-private workspace directory and are guarded
 * by [WorkspacePathGuard] + [WorkspaceTools] limits. They are opt-in (gated by the user's
 * "Workspace tools" preference) and are always read-only: they never write, delete or execute.
 *
 * Tool names are namespaced with a `builtin_` prefix so they can never collide with an MCP tool the
 * user registered under the same short name.
 */
object BuiltinTools {
    const val SERVER_ID = "builtin"

    const val SEARCH_FILES = "builtin_search_files"
    const val READ_FILE = "builtin_read_file"
    const val LIST_STRUCTURE = "builtin_list_structure"

    sealed interface Result {
        data class Success(val text: String) : Result
        data class Failure(val message: String) : Result
    }

    /** The catalog exposed to the Sidekick tool loop, shaped as [McpTool] for a uniform loop. */
    fun catalog(): List<McpTool> = listOf(
        McpTool(
            name = SEARCH_FILES,
            description = "Search the workspace for a text string (case-insensitive), returning path:line matches. Read-only.",
            inputSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Substring to search for"}},"required":["query"]}""",
        ),
        McpTool(
            name = READ_FILE,
            description = "Read a text file inside the workspace, up to 64000 characters. Read-only.",
            inputSchema = """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative file path"}},"required":["path"]}""",
        ),
        McpTool(
            name = LIST_STRUCTURE,
            description = "List the project structure (repo map) of the workspace as an indented tree. Read-only.",
            inputSchema = """{"type":"object","properties":{}}""",
        ),
    )

    fun isBuiltin(name: String): Boolean = name in setOf(SEARCH_FILES, READ_FILE, LIST_STRUCTURE)

    /**
     * Executes a built-in tool against [root] with the given JSON arguments and Eco Mode limits.
     * Returns a [Result.Failure] (never throws) for unknown tools, unsafe paths or bad arguments.
     */
    fun execute(name: String, root: File, arguments: JSONObject, ecoEnabled: Boolean): Result {
        return when (name) {
            SEARCH_FILES -> {
                val query = arguments.optString("query").trim()
                val text = WorkspaceTools.searchFiles(
                    root = root,
                    query = query,
                    maxResults = if (ecoEnabled) 12 else WorkspaceTools.DEFAULT_MAX_SEARCH_RESULTS,
                    maxFileBytes = if (ecoEnabled) 16_000L else WorkspaceTools.DEFAULT_MAX_FILE_BYTES,
                    maxTotalBytes = if (ecoEnabled) 60_000L else WorkspaceTools.DEFAULT_MAX_TOTAL_BYTES,
                )
                Result.Success(text)
            }
            READ_FILE -> {
                val path = arguments.optString("path").trim()
                val text = WorkspaceTools.readFile(
                    root = root,
                    relativePath = path,
                    maxBytes = if (ecoEnabled) 12_000 else WorkspaceTools.DEFAULT_READ_BYTES,
                )
                Result.Success(text)
            }
            LIST_STRUCTURE -> Result.Success(
                WorkspaceTools.listStructure(
                    root = root,
                    maxDepth = if (ecoEnabled) 3 else WorkspaceTools.DEFAULT_MAX_DEPTH,
                    maxEntries = if (ecoEnabled) 120 else WorkspaceTools.DEFAULT_MAX_ENTRIES,
                ),
            )
            else -> Result.Failure("Unknown built-in tool \"$name\".")
        }
    }
}
