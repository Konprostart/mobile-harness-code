package com.mobileharness.code.data

import com.mobileharness.code.hooks.HookAction
import com.mobileharness.code.hooks.HookEvent
import com.mobileharness.code.hooks.HookRule
import com.mobileharness.code.lsp.LspServerDefinition
import com.mobileharness.code.testing.BuildSystem
import com.mobileharness.code.testing.TestHistoryEntry
import com.mobileharness.code.workspace.WorkspaceGrant
import com.mobileharness.code.workspace.WorkspaceGroup
import com.mobileharness.code.workspace.WorkspacePermission
import com.mobileharness.code.workspace.WorkspaceRef
import com.mobileharness.code.workspace.WorkspaceRole
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codecs for the Phase 6 subsystems (hooks, workspace management, LSP, testing history).
 * They are pure, fail-safe on decode (malformed entries are skipped) and backward-compatible by
 * construction: every new field defaults to a safe value when absent. No secrets are stored in any
 * of these documents; API keys stay in [ApiKeyVault].
 */

object HookRuleCodec {
    fun toJson(rules: List<HookRule>): String {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("event", rule.event.name)
                    .put("matcher", rule.matcher)
                    .put("action", rule.action.name)
                    .put("message", rule.message)
                    .put("enabled", rule.enabled),
            )
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<HookRule> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val rules = mutableListOf<HookRule>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            val event = runCatching { HookEvent.valueOf(obj.optString("event")) }.getOrNull() ?: continue
            val action = runCatching { HookAction.valueOf(obj.optString("action")) }.getOrNull() ?: continue
            rules += HookRule(
                id = id,
                event = event,
                matcher = obj.optString("matcher", "*").ifBlank { "*" },
                action = action,
                message = obj.optString("message"),
                enabled = obj.optBoolean("enabled", true),
            )
        }
        return rules
    }
}

object WorkspaceManagementCodec {
    fun groupsToJson(groups: List<WorkspaceGroup>): String {
        val array = JSONArray()
        groups.forEach { group ->
            val refs = JSONArray()
            group.workspaces.forEach { ref ->
                refs.put(
                    JSONObject()
                        .put("id", ref.id)
                        .put("name", ref.name)
                        .put("path", ref.path)
                        .put("role", ref.role.name),
                )
            }
            array.put(
                JSONObject()
                    .put("id", group.id)
                    .put("projectId", group.projectId)
                    .put("name", group.name)
                    .put("workspaces", refs),
            )
        }
        return array.toString()
    }

    fun groupsFromJson(json: String?): List<WorkspaceGroup> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val groups = mutableListOf<WorkspaceGroup>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            val refs = mutableListOf<WorkspaceRef>()
            val refsArray = obj.optJSONArray("workspaces") ?: JSONArray()
            for (j in 0 until refsArray.length()) {
                val refObj = runCatching { refsArray.optJSONObject(j) }.getOrNull() ?: continue
                refs += WorkspaceRef(
                    id = refObj.optString("id"),
                    name = refObj.optString("name"),
                    path = refObj.optString("path"),
                    role = runCatching { WorkspaceRole.valueOf(refObj.optString("role")) }.getOrNull() ?: WorkspaceRole.MAIN,
                )
            }
            groups += WorkspaceGroup(
                id = id,
                projectId = obj.optString("projectId"),
                name = obj.optString("name"),
                workspaces = refs,
            )
        }
        return groups
    }

    fun grantsToJson(grants: List<WorkspaceGrant>): String {
        val array = JSONArray()
        grants.forEach { grant ->
            array.put(
                JSONObject()
                    .put("id", grant.id)
                    .put("agentId", grant.agentId)
                    .put("permission", grant.permission.name)
                    .put("workspaceId", grant.workspaceId ?: JSONObject.NULL)
                    .put("projectId", grant.projectId ?: JSONObject.NULL)
                    .put("enabled", grant.enabled),
            )
        }
        return array.toString()
    }

    fun grantsFromJson(json: String?): List<WorkspaceGrant> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val grants = mutableListOf<WorkspaceGrant>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            grants += WorkspaceGrant(
                id = id,
                agentId = obj.optString("agentId"),
                permission = runCatching { WorkspacePermission.valueOf(obj.optString("permission")) }.getOrNull() ?: WorkspacePermission.READ,
                workspaceId = obj.optString("workspaceId").ifBlank { null },
                projectId = obj.optString("projectId").ifBlank { null },
                enabled = obj.optBoolean("enabled", true),
            )
        }
        return grants
    }
}

object LspServerCodec {
    fun toJson(servers: List<LspServerDefinition>): String {
        val array = JSONArray()
        servers.forEach { server ->
            val extensions = JSONObject()
            server.extensions.forEach { (ext, lang) -> extensions.put(ext, lang) }
            val languageIds = JSONArray()
            server.languageIds.forEach { languageIds.put(it) }
            val env = JSONObject()
            server.env.forEach { (k, v) -> env.put(k, v) }
            array.put(
                JSONObject()
                    .put("name", server.name)
                    .put("command", server.command)
                    .put("args", JSONArray(server.args))
                    .put("env", env)
                    .put("extensions", extensions)
                    .put("languageIds", languageIds)
                    .put("initializationOptions", server.initializationOptions ?: JSONObject.NULL)
                    .put("license", server.license)
                    .put("enabled", server.enabled),
            )
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<LspServerDefinition> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val servers = mutableListOf<LspServerDefinition>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            val name = obj.optString("name")
            val command = obj.optString("command")
            if (name.isBlank() || command.isBlank()) continue
            val args = mutableListOf<String>()
            val argsArray = obj.optJSONArray("args") ?: JSONArray()
            for (j in 0 until argsArray.length()) args += argsArray.optString(j)
            val extensions = mutableMapOf<String, String>()
            val extObj = obj.optJSONObject("extensions") ?: JSONObject()
            extObj.keys().forEach { key -> extensions[key] = extObj.optString(key) }
            val languageIds = mutableListOf<String>()
            val langArray = obj.optJSONArray("languageIds") ?: JSONArray()
            for (j in 0 until langArray.length()) languageIds += langArray.optString(j)
            val env = mutableMapOf<String, String>()
            val envObj = obj.optJSONObject("env") ?: JSONObject()
            envObj.keys().forEach { key -> env[key] = envObj.optString(key) }
            servers += LspServerDefinition(
                name = name,
                command = command,
                args = args,
                env = env,
                extensions = extensions,
                languageIds = languageIds,
                initializationOptions = obj.optJSONObject("initializationOptions"),
                license = obj.optString("license"),
                enabled = obj.optBoolean("enabled", true),
            )
        }
        return servers
    }
}

object TestingHistoryCodec {
    fun toJson(entries: List<TestHistoryEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("timestampMillis", entry.timestampMillis)
                    .put("buildSystem", entry.buildSystem.name)
                    .put("stepsRun", entry.stepsRun)
                    .put("passed", entry.passed)
                    .put("failed", entry.failed)
                    .put("repairsApplied", entry.repairsApplied)
                    .put("result", entry.result),
            )
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<TestHistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val entries = mutableListOf<TestHistoryEntry>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            entries += TestHistoryEntry(
                timestampMillis = obj.optLong("timestampMillis"),
                buildSystem = runCatching { BuildSystem.valueOf(obj.optString("buildSystem")) }.getOrNull() ?: BuildSystem.UNKNOWN,
                stepsRun = obj.optInt("stepsRun"),
                passed = obj.optInt("passed"),
                failed = obj.optInt("failed"),
                repairsApplied = obj.optInt("repairsApplied"),
                result = obj.optString("result"),
            )
        }
        return entries
    }
}

/**
 * Codec for the persisted multi-agent orchestration plan. Stores the goal plus the current DAG
 * (tasks with status, assignment, result, error and declared lock paths) so a running plan can
 * survive an app restart and be resumed or inspected. Fail-safe on decode: malformed tasks are
 * skipped and never resurrect a half-valid plan. No secrets are stored — results may contain agent
 * output, which is device-local only.
 */
object OrchestrationPlanCodec {
    fun toJson(goal: String, tasks: List<com.mobileharness.code.orchestration.OrchestrationTask>): String {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("title", task.title)
                    .put("description", task.description)
                    .put("dependencies", JSONArray(task.dependencies))
                    .put("requiredCapability", task.requiredCapability?.name)
                    .put("priority", task.priority)
                    .put("assignedAgentId", task.assignedAgentId ?: JSONObject.NULL)
                    .put("status", task.status.name)
                    .put("result", task.result)
                    .put("error", task.error)
                    .put("expectedOutput", task.expectedOutput)
                    .put("lockPaths", JSONArray(task.lockPaths)),
            )
        }
        return JSONObject().put("goal", goal).put("tasks", array).toString()
    }

    fun fromJson(json: String?): Pair<String, List<com.mobileharness.code.orchestration.OrchestrationTask>> {
        if (json.isNullOrBlank()) return "" to emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return "" to emptyList()
        val goal = root.optString("goal")
        val array = root.optJSONArray("tasks") ?: return goal to emptyList()
        val tasks = mutableListOf<com.mobileharness.code.orchestration.OrchestrationTask>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.optJSONObject(i) }.getOrNull() ?: continue
            val id = obj.optString("id")
            val title = obj.optString("title")
            if (id.isBlank() || title.isBlank()) continue
            tasks += com.mobileharness.code.orchestration.OrchestrationTask(
                id = id,
                title = title,
                description = obj.optString("description"),
                dependencies = jsonStringArray(obj.optJSONArray("dependencies")),
                requiredCapability = runCatching {
                    com.mobileharness.code.orchestration.AgentCapability.valueOf(obj.optString("requiredCapability"))
                }.getOrNull(),
                priority = obj.optInt("priority"),
                assignedAgentId = obj.optString("assignedAgentId").takeIf { it.isNotBlank() && obj.opt("assignedAgentId") != JSONObject.NULL },
                status = runCatching {
                    com.mobileharness.code.orchestration.TaskStatus.valueOf(obj.optString("status"))
                }.getOrNull() ?: com.mobileharness.code.orchestration.TaskStatus.PENDING,
                result = obj.optString("result"),
                error = obj.optString("error"),
                expectedOutput = obj.optString("expectedOutput"),
                lockPaths = jsonStringArray(obj.optJSONArray("lockPaths")),
            )
        }
        return goal to tasks
    }

    private fun jsonStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList { for (i in 0 until array.length()) add(array.optString(i)) }
    }
}
