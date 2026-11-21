package com.mobileharness.code.prompt

/**
 * Auto Prompt Engineering models. The prompt engine assembles agent prompts from project context,
 * workspace instructions and task decomposition, applies a token budget, removes contradictions
 * between instruction sources, and versions every assembly so a good prompt can be restored.
 */

/** Where an instruction came from; lower [priority] numbers win on conflict. */
enum class InstructionSource(val priority: Int) {
    SYSTEM(0),
    SECURITY(0),
    WORKSPACE_RULES(10),
    AGENTS_MD(20),
    SKILL(30),
    PLUGIN(40),
    MEMORY(50),
    TASK(60),
    USER(70),
}

data class Instruction(
    val source: InstructionSource,
    val text: String,
    val key: String? = null,
)

data class PromptContext(
    val task: String,
    val projectId: String? = null,
    val workspacePath: String? = null,
    val agentKind: String = "claude",
    val repoSummary: String = "",
    val memory: String = "",
    val maxTokens: Int = 8_000,
)

data class AssembledPrompt(
    val system: String,
    val developer: String,
    val task: String,
    val instructions: List<Instruction>,
    val versionId: String,
)

data class SubTask(
    val id: String,
    val title: String,
    val prompt: String,
    val dependsOn: List<String> = emptyList(),
)
