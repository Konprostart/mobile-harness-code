package com.mobileharness.code.data

import android.content.Context
import com.mobileharness.code.model.AgentKind
import com.mobileharness.code.model.ChatMessage
import com.mobileharness.code.model.ChatAttachment
import com.mobileharness.code.model.Project
import com.mobileharness.code.model.ProjectKind
import com.mobileharness.code.model.ProjectType
import com.mobileharness.code.model.AndroidTemplate
import com.mobileharness.code.model.ProjectChat
import com.mobileharness.code.model.ProviderKind
import com.mobileharness.code.model.ProviderProfile
import com.mobileharness.code.model.defaultDshApiForProvider
import com.mobileharness.code.model.projectSlug
import com.mobileharness.code.model.providersForAgent
import com.mobileharness.code.mcp.McpServerCodec
import com.mobileharness.code.mcp.McpServerConfig
import com.mobileharness.code.network.GitProviderConfig
import com.mobileharness.code.network.GitProviderId
import com.mobileharness.code.network.ssh.SshProfile
import com.mobileharness.code.network.ssh.SshProfileCodec
import com.mobileharness.code.sidekick.RagCodec
import com.mobileharness.code.sidekick.RagDocument
import com.mobileharness.code.sidekick.RagIndex
import com.mobileharness.code.plugins.Plugin
import com.mobileharness.code.plugins.PluginCodec
import com.mobileharness.code.skills.BuiltinSkills
import com.mobileharness.code.skills.InstalledSkill
import com.mobileharness.code.skills.InstalledSkillCodec
import com.mobileharness.code.skills.Skill
import com.mobileharness.code.skills.SkillCodec
import com.mobileharness.code.token.BudgetConfig
import com.mobileharness.code.token.BudgetConfigCodec
import com.mobileharness.code.token.ContextLimitCodec
import com.mobileharness.code.token.ModelPricing
import com.mobileharness.code.token.OptimizationConfig
import com.mobileharness.code.token.OptimizationConfigCodec
import com.mobileharness.code.token.PricingOverrideCodec
import com.mobileharness.code.token.TokenOptimizationServiceConfig
import com.mobileharness.code.token.TokenOptimizationServiceCodec
import com.mobileharness.code.voice.VoiceSettings
import com.mobileharness.code.voice.VoiceSettingsCodec
import com.mobileharness.code.hooks.HookRule
import com.mobileharness.code.lsp.LspServerDefinition
import com.mobileharness.code.testing.TestHistoryEntry
import com.mobileharness.code.workspace.WorkspaceGrant
import com.mobileharness.code.workspace.WorkspaceGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class AppPreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences("pocket_preferences", Context.MODE_PRIVATE)

    var onboardingComplete: Boolean
        get() = preferences.getBoolean("onboarding_complete", false)
        set(value) { preferences.edit().putBoolean("onboarding_complete", value).apply() }

    var runtimeSetupComplete: Boolean
        get() = preferences.getBoolean("runtime_setup_complete", false)
        set(value) { preferences.edit().putBoolean("runtime_setup_complete", value).apply() }

    var backgroundSetupComplete: Boolean
        get() = preferences.getBoolean("background_setup_complete", false)
        set(value) { preferences.edit().putBoolean("background_setup_complete", value).apply() }

    var initialLanguageSelected: Boolean
        get() = preferences.getBoolean("initial_language_selected", onboardingComplete || runtimeSetupComplete)
        set(value) { preferences.edit().putBoolean("initial_language_selected", value).apply() }

    /** Coding agent engine the user picked during setup. Absent = pre-agent-choice install → Claude. */
    var agentKind: String
        get() = preferences.getString("agent_kind", AgentKind.CLAUDE_CODE.stableId) ?: AgentKind.CLAUDE_CODE.stableId
        set(value) { preferences.edit().putString("agent_kind", value).apply() }

    /** Agent chosen for the initial runtime installation; used as the leading UI tab. */
    var primaryAgentKind: String
        get() = preferences.getString("primary_agent_kind", "") ?: ""
        set(value) { preferences.edit().putString("primary_agent_kind", value).apply() }

    var antigravityModel: String
        get() = preferences.getString("agent_antigravity_model", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_model", value).apply() }

    var antigravityEffort: String
        get() = preferences.getString("agent_antigravity_effort", "high") ?: "high"
        set(value) { preferences.edit().putString("agent_antigravity_effort", value).apply() }

    var antigravitySignedIn: Boolean
        get() = preferences.getBoolean("agent_antigravity_signed_in", false)
        set(value) { preferences.edit().putBoolean("agent_antigravity_signed_in", value).apply() }

    var antigravityAccountEmail: String
        get() = preferences.getString("agent_antigravity_account_email", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_account_email", value).apply() }

    /** versionCode whose "What's new" dialog the user has already seen. */
    var whatsNewSeenVersionCode: Int
        get() = preferences.getInt("whats_new_seen_code", 0)
        set(value) { preferences.edit().putInt("whats_new_seen_code", value).apply() }

    /** Whether the user has opened Backup & Restore since it was introduced (clears the "new" dot). */
    var backupFeatureSeen: Boolean
        get() = preferences.getBoolean("backup_feature_seen", false)
        set(value) { preferences.edit().putBoolean("backup_feature_seen", value).apply() }

    var githubLogin: String
        get() = preferences.getString("github_login", "") ?: ""
        set(value) { preferences.edit().putString("github_login", value).apply() }

    // --- Self-host Git providers (Gitea / OneDev / future own server) ----------------------
    /**
     * Non-secret connection settings for a self-host Git provider. The access token lives in
     * [ApiKeyVault] (backed up with it), never here. Host and login are portable so they are
     * intentionally excluded from [DEVICE_ONLY_KEYS].
     */
    fun saveGitProviderConfig(id: GitProviderId, config: GitProviderConfig) {
        preferences.edit()
            .putString("git_provider_${id.stableId}_host", config.host.trim())
            .putString("git_provider_${id.stableId}_login", config.login.trim())
            .apply()
    }

    fun loadGitProviderConfig(id: GitProviderId): GitProviderConfig = GitProviderConfig(
        host = preferences.getString("git_provider_${id.stableId}_host", "").orEmpty(),
        login = preferences.getString("git_provider_${id.stableId}_login", "").orEmpty(),
    )

    fun saveAgentConversation(agent: AgentKind, projectId: String, chatId: String, conversationId: String?) {
        val key = agentConversationKey(agent, projectId, chatId)
        preferences.edit().apply {
            if (conversationId.isNullOrBlank()) remove(key) else putString(key, conversationId)
        }.commit()
    }

    fun loadAgentConversation(agent: AgentKind, projectId: String, chatId: String): String? =
        preferences.getString(agentConversationKey(agent, projectId, chatId), null)

    fun clearAgentConversations(agent: AgentKind) {
        val stable = agent.stableId
        val keys = preferences.all.keys.filter { key ->
            key.startsWith("agent_conversation_${stable}_") ||
                key.startsWith("agent_conversation_v2_${stable}_")
        }
        if (keys.isEmpty()) return
        preferences.edit().apply { keys.forEach(::remove) }.apply()
    }

    private fun agentConversationKey(agent: AgentKind, projectId: String, chatId: String): String {
        // Antigravity v2 sessions are created with an explicit CLI project so
        // old default-project conversations cannot redirect writes to scratch.
        val version = if (agent == AgentKind.ANTIGRAVITY) "v2_" else ""
        return "agent_conversation_${version}${agent.stableId}_${projectId}_$chatId"
    }

    /** Pinned dsh version recorded when DeepSeek Harness was installed. */
    var dshVersion: String
        get() = preferences.getString("dsh_version", "") ?: ""
        set(value) { preferences.edit().putString("dsh_version", value).apply() }

    var themeMode: String
        get() = preferences.getString("theme_mode", "dark") ?: "dark"
        set(value) { preferences.edit().putString("theme_mode", value).apply() }

    /**
     * Whether Material 3 dynamic color (Monet) follows the system wallpaper on Android 12+.
     * Off by default so the theme is predictable and never shifts on its own.
     */
    var dynamicColorEnabled: Boolean
        get() = preferences.getBoolean("dynamic_color_enabled", false)
        set(value) { preferences.edit().putBoolean("dynamic_color_enabled", value).apply() }

    /** Whether the on-device Gradle heap follows the RAM-derived recommendation (true) or a manual override. */
    var gradleMemoryAuto: Boolean
        get() = preferences.getBoolean("gradle_memory_auto", true)
        set(value) { preferences.edit().putBoolean("gradle_memory_auto", value).apply() }

    /** Manual max heap in MiB; 0 means "use the RAM-derived recommendation". */
    var gradleMaxHeapMb: Int
        get() = preferences.getInt("gradle_max_heap_mb", 0)
        set(value) { preferences.edit().putInt("gradle_max_heap_mb", value).apply() }

    /** Manual max metaspace in MiB; 0 means "use the RAM-derived recommendation". */
    var gradleMaxMetaspaceMb: Int
        get() = preferences.getInt("gradle_max_metaspace_mb", 0)
        set(value) { preferences.edit().putInt("gradle_max_metaspace_mb", value).apply() }

    // --- Optional server sync (opt-in, off by default) ---------------------------------------
    var serverSyncEnabled: Boolean
        get() = preferences.getBoolean("server_sync_enabled", false)
        set(value) { preferences.edit().putBoolean("server_sync_enabled", value).apply() }

    var serverSyncMode: String
        get() = preferences.getString("server_sync_mode", "SSH") ?: "SSH"
        set(value) { preferences.edit().putString("server_sync_mode", value).apply() }

    var serverSyncSshHost: String
        get() = preferences.getString("server_sync_ssh_host", "") ?: ""
        set(value) { preferences.edit().putString("server_sync_ssh_host", value).apply() }

    var serverSyncSshPort: Int
        get() = preferences.getInt("server_sync_ssh_port", 22)
        set(value) { preferences.edit().putInt("server_sync_ssh_port", value).apply() }

    var serverSyncSshUser: String
        get() = preferences.getString("server_sync_ssh_user", "root") ?: "root"
        set(value) { preferences.edit().putString("server_sync_ssh_user", value).apply() }

    var serverSyncSshKeyPath: String
        get() = preferences.getString("server_sync_ssh_key_path", "") ?: ""
        set(value) { preferences.edit().putString("server_sync_ssh_key_path", value).apply() }

    var serverSyncSshRemoteDir: String
        get() = preferences.getString("server_sync_ssh_remote_dir", "") ?: ""
        set(value) { preferences.edit().putString("server_sync_ssh_remote_dir", value).apply() }

    var serverSyncDukoiBaseUrl: String
        get() = preferences.getString("server_sync_dukoi_base_url", "") ?: ""
        set(value) { preferences.edit().putString("server_sync_dukoi_base_url", value).apply() }

    // --- Eco Mode / Token Saver (opt-in, off by default) ---------------------------------
    var ecoModeEnabled: Boolean
        get() = preferences.getBoolean("eco_mode_enabled", false)
        set(value) { preferences.edit().putBoolean("eco_mode_enabled", value).apply() }

    // --- Auto Prompt Engine (opt-in, off by default — fail-closed) ------------------------
    /**
     * When off (default) the coding agents run their existing prompt flow unchanged. When on, the
     * runtime reads AGENTS.md/CLAUDE.md from the workspace as data and merges it into prompts.
     * Not sensitive, so it is intentionally excluded from [DEVICE_ONLY_KEYS].
     */
    var autoPromptEnabled: Boolean
        get() = preferences.getBoolean("auto_prompt_enabled", false)
        set(value) { preferences.edit().putBoolean("auto_prompt_enabled", value).apply() }

    // --- Auto Prompt Library (scheduled/manual prompt library — distinct from the engine above) ----
    /**
     * Master switch for the Auto Prompt library. When off, no scheduled prompt fires automatically,
     * but saved prompts and their settings are preserved and manual RUN still works.
     */
    var autoPromptSystemEnabled: Boolean
        get() = preferences.getBoolean("auto_prompt_system_enabled", false)
        set(value) { preferences.edit().putBoolean("auto_prompt_system_enabled", value).apply() }

    /** Serialized scheduled tasks (prompt library) — device-only user content, no secrets. */
    var autoPromptTasksJson: String
        get() = preferences.getString("auto_prompt_tasks_json", "") ?: ""
        set(value) { preferences.edit().putString("auto_prompt_tasks_json", value).apply() }

    /** Serialized auto-prompt run history — device-only, no API keys. */
    var autoPromptHistoryJson: String
        get() = preferences.getString("auto_prompt_history_json", "") ?: ""
        set(value) { preferences.edit().putString("auto_prompt_history_json", value).apply() }

    // --- Token & Cost Manager (usage tracking + cost + optimization, all opt-in) ---------------
    /**
     * Master AUTO TOKEN OPTIMIZATION config. Off by default; when off, no agent behavior changes
     * (usage tracking remains active). Not sensitive, so excluded from [DEVICE_ONLY_KEYS].
     */
    var tokenOptimizationConfig: OptimizationConfig
        get() = OptimizationConfigCodec.fromJson(
            preferences.getString("token_optimization_json", "").orEmpty(),
        )
        set(value) { preferences.edit().putString("token_optimization_json", OptimizationConfigCodec.toJson(value)).apply() }

    /** Local spend budgets (daily/weekly/monthly + per-provider/per-model limits). */
    var tokenBudget: BudgetConfig
        get() = BudgetConfigCodec.fromJson(preferences.getString("token_budget_json", "").orEmpty())
        set(value) { preferences.edit().putString("token_budget_json", BudgetConfigCodec.toJson(value)).apply() }

    /** User-corrected per-model prices; keys are the normalized `providerId/model` pair. */
    var tokenPricingOverrides: Map<String, ModelPricing>
        get() = PricingOverrideCodec.fromJson(preferences.getString("token_pricing_overrides_json", "").orEmpty())
        set(value) { preferences.edit().putString("token_pricing_overrides_json", PricingOverrideCodec.toJson(value)).apply() }

    /** When the user last changed the pricing overrides (0 = never). */
    var tokenPricingUpdatedAtMillis: Long
        get() = preferences.getLong("token_pricing_updated_millis", 0L)
        set(value) { preferences.edit().putLong("token_pricing_updated_millis", value).apply() }

    /** Running tokens credited to context compaction (for the savings report). */
    var tokenCompactionSaved: Long
        get() = preferences.getLong("token_compaction_saved", 0L)
        set(value) { preferences.edit().putLong("token_compaction_saved", value.coerceAtLeast(0L)).apply() }

    /** Running tokens credited to a deliberate cheaper-model choice (for the savings report). */
    var tokenModelChoiceSaved: Long
        get() = preferences.getLong("token_model_choice_saved", 0L)
        set(value) { preferences.edit().putLong("token_model_choice_saved", value.coerceAtLeast(0L)).apply() }

    /**
     * Central Token Optimization service config (Phase 6.2). A separate, default-OFF master switch
     * plus per-feature sub-switches for context shaping, budget gating and model advising. It never
     * overlaps the older [tokenOptimizationConfig] (AUTO TOKEN OPTIMIZATION panel) or Eco Mode.
     */
    var tokenOptimizationServiceConfig: TokenOptimizationServiceConfig
        get() = TokenOptimizationServiceCodec.fromJson(
            preferences.getString("token_optimization_service_json", "").orEmpty(),
        )
        set(value) { preferences.edit().putString("token_optimization_service_json", TokenOptimizationServiceCodec.toJson(value)).apply() }

    /** Accumulated spend (USD) attributed to the active orchestration run, for the per-task budget. */
    var orchestrationTaskSpendUsd: Double
        get() = preferences.getFloat("orchestration_task_spend_usd", 0f).toDouble()
        set(value) { preferences.edit().putFloat("orchestration_task_spend_usd", value.coerceAtLeast(0.0).toFloat()).apply() }


    /** User-corrected context-window limits (providerId/model → maxTokens). */
    var contextWindowOverrides: Map<String, Long>
        get() = ContextLimitCodec.fromJson(preferences.getString("context_window_overrides_json", "").orEmpty())
        set(value) { preferences.edit().putString("context_window_overrides_json", ContextLimitCodec.toJson(value)).apply() }

    /** Context limits learned from explicit provider overflow errors (never user-touched). */
    var contextWindowLearned: Map<String, Long>
        get() = ContextLimitCodec.fromJson(preferences.getString("context_window_learned_json", "").orEmpty())
        set(value) { preferences.edit().putString("context_window_learned_json", ContextLimitCodec.toJson(value)).apply() }

    // --- Sidekick assistant ---------------------------------------------------------------
    var sidekickFloatingButton: Boolean
        get() = preferences.getBoolean("sidekick_floating_button", false)
        set(value) { preferences.edit().putBoolean("sidekick_floating_button", value).apply() }

    /** Vertical position of the floating Sidekick button, normalized 0f (top) .. 1f (bottom). */
    var sidekickFabOffset: Float
        get() = preferences.getFloat("sidekick_fab_offset", 0.5f)
        set(value) { preferences.edit().putFloat("sidekick_fab_offset", value.coerceIn(0f, 1f)).apply() }

    /** When true the assistant uses its own provider instead of the coding agent's. */
    var sidekickUseCustomProvider: Boolean
        get() = preferences.getBoolean("sidekick_custom_provider", false)
        set(value) { preferences.edit().putBoolean("sidekick_custom_provider", value).apply() }

    var sidekickBackgroundResearch: Boolean
        get() = preferences.getBoolean("sidekick_bg_research", false)
        set(value) { preferences.edit().putBoolean("sidekick_bg_research", value).apply() }

    /**
     * Language preference for the Sidekick assistant ("auto" or a BCP-47 tag such as "sr-RS").
     * Persisted so an explicit choice survives screen reopen and app restart instead of resetting
     * to "auto". Not sensitive, so it is intentionally excluded from [DEVICE_ONLY_KEYS].
     */
    var sidekickLanguage: String
        get() = preferences.getString("sidekick_language", "auto") ?: "auto"
        set(value) { preferences.edit().putString("sidekick_language", value.trim()).apply() }

    // --- Voice (STT/TTS + Live Mode) -------------------------------------------------
    /**
     * Device-local voice preferences: STT/TTS provider kind, model, voice, language, speech rate,
     * auto-read, and Live Mode settings. Keys are NOT stored here (they live in [ApiKeyVault]).
     * Not sensitive, so excluded from [DEVICE_ONLY_KEYS].
     */
    var voiceSettings: VoiceSettings
        get() = VoiceSettingsCodec.fromJson(preferences.getString("voice_settings_json", "").orEmpty())
        set(value) { preferences.edit().putString("voice_settings_json", VoiceSettingsCodec.toJson(value)).apply() }

    /** When true the assistant falls back to [sidekickFallbackModel] on transient provider errors. */
    var sidekickFallbackEnabled: Boolean
        get() = preferences.getBoolean("sidekick_fallback_enabled", false)
        set(value) { preferences.edit().putBoolean("sidekick_fallback_enabled", value).apply() }

    /** Model id the assistant tries after the primary model fails with a transient error. */
    var sidekickFallbackModel: String
        get() = preferences.getString("sidekick_fallback_model", "") ?: ""
        set(value) { preferences.edit().putString("sidekick_fallback_model", value.trim()).apply() }

    /** When true the assistant may fall back to the on-device/LAN model server (Ollama et al.). */
    var localModelEnabled: Boolean
        get() = preferences.getBoolean("local_model_enabled", false)
        set(value) { preferences.edit().putBoolean("local_model_enabled", value).apply() }

    /** OpenAI-compatible base URL of the local model server (default: Ollama on loopback). */
    var localModelBaseUrl: String
        get() = preferences.getString("local_model_base_url", "") ?: ""
        set(value) { preferences.edit().putString("local_model_base_url", value.trim()).apply() }

    /** Model id served by the local model server. */
    var localModelName: String
        get() = preferences.getString("local_model_name", "") ?: ""
        set(value) { preferences.edit().putString("local_model_name", value.trim()).apply() }

    // --- Model / provider rotation (coding agents) ---------------------------------------
    /**
     * When true the coding agents may automatically rotate to the next compatible model or
     * provider after a transient failure. Off by default: existing transport recovery and the
     * manual retry card keep their exact current behaviour until the user opts in.
     */
    var agentRotationEnabled: Boolean
        get() = preferences.getBoolean("agent_rotation_enabled", false)
        set(value) { preferences.edit().putBoolean("agent_rotation_enabled", value).apply() }

    /**
     * How many new-session rotation attempts are allowed per failure. Defaults to 2, which lines
     * up with the existing 5s/15s reconnect budget; higher values extend the chain with a capped
     * backoff. Clamped to a safe range so a misconfigured value can never loop forever.
     */
    var agentRotationMaxAttempts: Int
        get() = preferences.getInt("agent_rotation_max_attempts", 2).coerceIn(1, 8)
        set(value) { preferences.edit().putInt("agent_rotation_max_attempts", value.coerceIn(1, 8)).apply() }

    // --- MCP servers (Faza 3) -------------------------------------------------------------
    /**
     * Persists the registered MCP servers as a single JSON document. The per-server bearer token
     * is stored here too, but the whole key is excluded from backup (see DEVICE_ONLY_KEYS) so a
     * server-specific credential never leaves the device through Backup & Restore.
     */
    fun saveMcpServers(servers: List<McpServerConfig>) {
        preferences.edit().putString("mcp_servers_json", McpServerCodec.toJson(servers)).apply()
    }

    /** Loads the registered MCP servers, tolerating malformed or removed entries. */
    fun loadMcpServers(): List<McpServerConfig> {
        val raw = preferences.getString("mcp_servers_json", null) ?: return emptyList()
        return McpServerCodec.fromJson(raw)
    }

    /** Newline/line-separated list of directories and https:// URLs the assistant may search. */
    var sidekickResearchSources: String
        get() = preferences.getString("sidekick_research_sources", "") ?: ""
        set(value) { preferences.edit().putString("sidekick_research_sources", value).apply() }

    // --- Document base (RAG, Faza 4) --------------------------------------------------------
    /** When true the assistant augments answers with relevant passages from the saved documents. */
    var ragEnabled: Boolean
        get() = preferences.getBoolean("rag_enabled", false)
        set(value) { preferences.edit().putBoolean("rag_enabled", value).apply() }

    private val ragDir = File(context.filesDir, "rag").also { it.mkdirs() }

    /** Persists the assistant's document base to a private file (never backed up, device-only). */
    fun saveRagDocuments(documents: List<RagDocument>) {
        val file = File(ragDir, "documents.json")
        val temporary = File(ragDir, "documents.json.tmp")
        runCatching {
            temporary.writeText(RagCodec.toJson(documents.take(RagIndex.MAX_DOCUMENTS)))
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }

    /** Loads the assistant's document base, tolerating malformed or truncated entries. */
    fun loadRagDocuments(): List<RagDocument> {
        val file = File(ragDir, "documents.json")
        if (!file.exists()) return emptyList()
        return RagCodec.fromJson(runCatching { file.readText() }.getOrDefault(""))
    }

    // --- Built-in workspace tools (Faza C) ---------------------------------------------------
    /**
     * When true the Sidekick assistant may use the built-in, read-only workspace tools
     * (search files, read file, list project structure). Off by default; the tools are always
     * confined to the app-private `workspaces/` directory by a fail-closed path guard.
     */
    var workspaceToolsEnabled: Boolean
        get() = preferences.getBoolean("workspace_tools_enabled", false)
        set(value) { preferences.edit().putBoolean("workspace_tools_enabled", value).apply() }

    // --- Skills (Faza C) ---------------------------------------------------------------------
    /** Master opt-in for the skill system; when off, no skill is injected into the assistant. */
    var skillsEnabled: Boolean
        get() = preferences.getBoolean("skills_enabled", false)
        set(value) { preferences.edit().putBoolean("skills_enabled", value).apply() }

    /**
     * Persists the full skill list (curated defaults plus any user edits) as JSON. The skill
     * instructions are user-authored text and are kept device-only (excluded from backup).
     */
    fun saveSkills(skills: List<Skill>) {
        preferences.edit().putString("skills_json", SkillCodec.toJson(skills)).apply()
    }

    /**
     * Loads the persisted skills, seeding the curated defaults on first run (all disabled). A
     * malformed or tampered document is dropped fail-closed to the defaults.
     */
    fun loadSkills(): List<Skill> {
        val raw = preferences.getString("skills_json", null) ?: return BuiltinSkills.defaults()
        val loaded = SkillCodec.fromJson(raw)
        return if (loaded.isEmpty()) BuiltinSkills.defaults() else loaded
    }

    // --- Advanced SKILL.md skills + plugin registry (Faza 2) --------------------------------
    /** Persists the installed SKILL.md skill metadata (device-only, excluded from backup). */
    fun saveInstalledSkills(skills: List<InstalledSkill>) {
        preferences.edit().putString("installed_skills_json", InstalledSkillCodec.toJson(skills)).apply()
    }

    fun loadInstalledSkills(): List<InstalledSkill> {
        val raw = preferences.getString("installed_skills_json", null) ?: return emptyList()
        return InstalledSkillCodec.fromJson(raw)
    }

    fun installedSkillsRaw(): String? = preferences.getString("installed_skills_json", null)

    /** Persists the installed plugin registry (device-only, excluded from backup). */
    fun saveInstalledPlugins(plugins: List<Plugin>) {
        preferences.edit().putString("installed_plugins_json", PluginCodec.encodeRegistry(plugins)).apply()
    }

    fun loadInstalledPlugins(): List<Plugin> {
        val raw = preferences.getString("installed_plugins_json", null) ?: return emptyList()
        return PluginCodec.decodeRegistry(raw)
    }

    fun installedPluginsRaw(): String? = preferences.getString("installed_plugins_json", null)

    fun saveInstalledPluginsRaw(json: String) {
        preferences.edit().putString("installed_plugins_json", json).apply()
    }

    // --- Phase 6: hooks, workspace management, LSP, testing history -------------------------
    /** Persisted Sidekick hook rules (device-only, no secrets). */
    var hookRulesJson: String
        get() = preferences.getString("hook_rules_json", "") ?: ""
        set(value) { preferences.edit().putString("hook_rules_json", value).apply() }

    fun saveHookRules(rules: List<HookRule>) {
        preferences.edit().putString("hook_rules_json", HookRuleCodec.toJson(rules)).apply()
    }

    fun loadHookRules(): List<HookRule> = HookRuleCodec.fromJson(preferences.getString("hook_rules_json", null))

    /** Persisted workspace groups (device-only, no secrets). */
    var workspaceGroupsJson: String
        get() = preferences.getString("workspace_groups_json", "") ?: ""
        set(value) { preferences.edit().putString("workspace_groups_json", value).apply() }

    fun saveWorkspaceGroups(groups: List<WorkspaceGroup>) {
        preferences.edit().putString("workspace_groups_json", WorkspaceManagementCodec.groupsToJson(groups)).apply()
    }

    fun loadWorkspaceGroups(): List<WorkspaceGroup> =
        WorkspaceManagementCodec.groupsFromJson(preferences.getString("workspace_groups_json", null))

    /** Persisted workspace grants (device-only, no secrets). */
    var workspaceGrantsJson: String
        get() = preferences.getString("workspace_grants_json", "") ?: ""
        set(value) { preferences.edit().putString("workspace_grants_json", value).apply() }

    fun saveWorkspaceGrants(grants: List<WorkspaceGrant>) {
        preferences.edit().putString("workspace_grants_json", WorkspaceManagementCodec.grantsToJson(grants)).apply()
    }

    fun loadWorkspaceGrants(): List<WorkspaceGrant> =
        WorkspaceManagementCodec.grantsFromJson(preferences.getString("workspace_grants_json", null))

    /** Persisted LSP server definitions (device-only, no secrets). */
    var lspServersJson: String
        get() = preferences.getString("lsp_servers_json", "") ?: ""
        set(value) { preferences.edit().putString("lsp_servers_json", value).apply() }

    fun saveLspServers(servers: List<LspServerDefinition>) {
        preferences.edit().putString("lsp_servers_json", LspServerCodec.toJson(servers)).apply()
    }

    fun loadLspServers(): List<LspServerDefinition> =
        LspServerCodec.fromJson(preferences.getString("lsp_servers_json", null))

    /** Persisted autonomous-testing history (device-only, no secrets). */
    var testingHistoryJson: String
        get() = preferences.getString("testing_history_json", "") ?: ""
        set(value) { preferences.edit().putString("testing_history_json", value).apply() }

    fun saveTestingHistory(entries: List<TestHistoryEntry>) {
        preferences.edit().putString("testing_history_json", TestingHistoryCodec.toJson(entries)).apply()
    }

    fun loadTestingHistory(): List<TestHistoryEntry> =
        TestingHistoryCodec.fromJson(preferences.getString("testing_history_json", null))

    // --- Phase 6.1: optional multi-agent orchestrator (device-only, no secrets) -------------
    /**
     * Master switch for the optional Orchestrator Mode. Defaults to OFF so the existing
     * Standard Mode (direct agent launches) is never routed through the orchestrator until the
     * user explicitly enables it.
     */
    var orchestratorEnabled: Boolean
        get() = preferences.getBoolean("orchestrator_enabled", false)
        set(value) { preferences.edit().putBoolean("orchestrator_enabled", value).apply() }

    /** Persisted orchestration plan (goal + DAG) for resume/inspection across app restarts. */
    var orchestrationPlanJson: String
        get() = preferences.getString("orchestration_plan_json", "") ?: ""
        set(value) { preferences.edit().putString("orchestration_plan_json", value).apply() }

    fun saveOrchestrationPlan(goal: String, tasks: List<com.mobileharness.code.orchestration.OrchestrationTask>) {
        preferences.edit().putString("orchestration_plan_json", OrchestrationPlanCodec.toJson(goal, tasks)).apply()
    }

    fun loadOrchestrationPlan(): Pair<String, List<com.mobileharness.code.orchestration.OrchestrationTask>> =
        OrchestrationPlanCodec.fromJson(preferences.getString("orchestration_plan_json", null))

    // --- SSH servers (Faza 1: sshj client + tunnel) ----------------------------------------
    /**
     * Persists the saved SSH server profiles as a single JSON document. Secrets (password,
     * private key, passphrase, pinned fingerprint) live in the dedicated `SshSecretStore`, not
     * here; the whole `ssh_profiles_json` key is also excluded from Backup & Restore so the
     * host/username metadata never leaves the device either.
     */
    fun saveSshProfiles(profiles: List<SshProfile>) {
        preferences.edit().putString("ssh_profiles_json", SshProfileCodec.toJson(profiles)).apply()
    }

    /** Loads the saved SSH server profiles, tolerating malformed or removed entries. */
    fun loadSshProfiles(): List<SshProfile> {
        val raw = preferences.getString("ssh_profiles_json", null) ?: return emptyList()
        return SshProfileCodec.fromJson(raw)
    }

    // --- Share & Invite (local scaffolding for the "soon" crypto rewards) ---------------
    /** Personal invite code (e.g. `MHC-XXXXXX`), generated once and kept on this device. */
    var inviteCode: String
        get() = preferences.getString("invite_code", "") ?: ""
        set(value) { preferences.edit().putString("invite_code", value).apply() }

    /** Friend's invite code bound to this install (one-time); blank until redeemed. */
    var redeemedInviteCode: String
        get() = preferences.getString("redeemed_invite_code", "") ?: ""
        set(value) { preferences.edit().putString("redeemed_invite_code", value).apply() }

    /** Local count of share/copy actions for the referral status card. */
    var inviteShareCount: Int
        get() = preferences.getInt("invite_share_count", 0)
        set(value) { preferences.edit().putInt("invite_share_count", value).apply() }

    /** Persists the assistant's independent provider profile (the API key is reused from the vault). */
    fun saveSidekickProvider(profile: ProviderProfile) {
        preferences.edit()
            .putString("sidekick_kind", profile.kind.name)
            .putString("sidekick_id", profile.providerId)
            .putString("sidekick_base_url", profile.baseUrl)
            .putString("sidekick_model", profile.model)
            .putString("sidekick_dsh_api", profile.dshApi)
            .apply()
    }

    /** Loads the assistant's independent provider, falling back to the coding agent's provider. */
    fun loadSidekickProvider(vault: ApiKeyVault, fallback: ProviderProfile): ProviderProfile {
        val kind = runCatching {
            preferences.getString("sidekick_kind", null)?.let(ProviderKind::valueOf)
        }.getOrNull() ?: return fallback
        val providerId = preferences.getString("sidekick_id", kind.name)?.takeIf(String::isNotBlank) ?: kind.name
        return ProviderProfile(
            kind = kind,
            baseUrl = preferences.getString("sidekick_base_url", kind.defaultBaseUrl) ?: kind.defaultBaseUrl,
            model = preferences.getString("sidekick_model", kind.defaultModel) ?: kind.defaultModel,
            hasSecret = vault.contains(providerId),
            providerId = providerId,
            dshApi = preferences.getString("sidekick_dsh_api", defaultDshApiForProvider(kind)) ?: defaultDshApiForProvider(kind),
        )
    }

    var languageCode: String
        get() = preferences.getString("language_code", "system") ?: "system"
        set(value) { preferences.edit().putString("language_code", value).apply() }

    /** Ids of the central AI library providers the user starred. */
    var aiProviderFavorites: Set<String>
        get() = preferences.getStringSet("ai_provider_favorites", emptySet()) ?: emptySet()
        set(value) { preferences.edit().putStringSet("ai_provider_favorites", value).apply() }

    fun toggleAiProviderFavorite(id: String) {
        aiProviderFavorites = if (id in aiProviderFavorites) aiProviderFavorites - id else aiProviderFavorites + id
    }

    /** Most-recently used central AI library provider ids, newest first. */
    var aiProviderRecents: List<String>
        get() = preferences.getString("ai_provider_recents", null)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?: emptyList()
        set(value) { preferences.edit().putString("ai_provider_recents", value.joinToString(",")).apply() }

    fun recordAiProviderRecent(id: String) {
        aiProviderRecents = (listOf(id) + aiProviderRecents.filter { it != id }).take(12)
    }

    var legacySeededCredentialRemoved: Boolean
        get() = preferences.getBoolean("legacy_seeded_credential_removed", false)
        set(value) { preferences.edit().putBoolean("legacy_seeded_credential_removed", value).apply() }

    var testProviderDefaultsVersion: Int
        get() = preferences.getInt("test_provider_defaults_version", 0)
        set(value) { preferences.edit().putInt("test_provider_defaults_version", value).apply() }

    var lastAppUpdateCheckMillis: Long
        get() = preferences.getLong("last_app_update_check_millis", 0L)
        set(value) { preferences.edit().putLong("last_app_update_check_millis", value).apply() }

    /**
     * Debug-only manifest URL override. Empty in release builds; populated via
     * Settings → Update channel in debug builds so a local server (exposed via
     * Cloudflare Tunnel or ngrok) can be tested without publishing a release.
     */
    var debugUpdateManifestUrl: String
        get() = preferences.getString("debug_update_manifest_url", "") ?: ""
        set(value) { preferences.edit().putString("debug_update_manifest_url", value).apply() }

    /** Development stacks the user picked during onboarding (names of DevStack). */
    var selectedDevStacks: Set<String>
        get() {
            val raw = preferences.getString("selected_dev_stacks", null) ?: return emptySet()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
            }.getOrDefault(emptySet())
        }
        set(value) {
            val arr = JSONArray()
            value.sorted().forEach(arr::put)
            preferences.edit().putString("selected_dev_stacks", arr.toString()).apply()
        }


    /** Every portable setting, typed, for backups. Device-only state stays out. */
    fun exportAll(): JSONObject {
        val result = JSONObject()
        preferences.all.forEach { (key, value) ->
            if (key in DEVICE_ONLY_KEYS || value == null) return@forEach
            val (type, encoded) = when (value) {
                is Boolean -> "bool" to value
                is Int -> "int" to value
                is Long -> "long" to value
                is Float -> "float" to value.toDouble()
                is String -> "string" to value
                is Set<*> -> "set" to JSONArray(value.filterIsInstance<String>())
                else -> return@forEach
            }
            result.put(key, JSONObject().put("t", type).put("v", encoded))
        }
        return result
    }

    /** Replaces portable settings with [backup] while keeping this device's install state. */
    fun importAll(backup: JSONObject) {
        val kept = preferences.all.filterKeys { it in DEVICE_ONLY_KEYS }
        val editor = preferences.edit().clear()
        kept.forEach { (key, value) -> editor.putTyped(key, value) }
        backup.keys().forEach { key ->
            if (key in DEVICE_ONLY_KEYS) return@forEach
            val item = backup.optJSONObject(key) ?: return@forEach
            when (item.optString("t")) {
                "bool" -> editor.putBoolean(key, item.optBoolean("v"))
                "int" -> editor.putInt(key, item.optInt("v"))
                "long" -> editor.putLong(key, item.optLong("v"))
                "float" -> editor.putFloat(key, item.optDouble("v").toFloat())
                "string" -> editor.putString(key, item.optString("v"))
                "set" -> item.optJSONArray("v")?.let { array ->
                    editor.putStringSet(key, (0 until array.length()).map(array::optString).toSet())
                }
            }
        }
        editor.commit()
    }

    private fun android.content.SharedPreferences.Editor.putTyped(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    fun saveProvider(profile: ProviderProfile, agent: AgentKind? = null) {
        val editor = preferences.edit()
            .putString("provider_kind", profile.kind.name)
            .putString("provider_id", profile.providerId)
            .putString("provider_base_url", profile.baseUrl)
            .putString("provider_model", profile.model)
            .putString("provider_dsh_api", profile.dshApi)
            .putString("provider_openrouter_order", profile.openRouterProviderOrder)
            .putBoolean("provider_openrouter_allow_fallbacks", profile.openRouterAllowFallbacks)
        if (agent != null) {
            val prefix = providerPrefix(agent)
            editor
                .putString("${prefix}kind", profile.kind.name)
                .putString("${prefix}id", profile.providerId)
                .putString("${prefix}base_url", profile.baseUrl)
                .putString("${prefix}model", profile.model)
                .putString("${prefix}dsh_api", profile.dshApi)
                .putString("${prefix}openrouter_order", profile.openRouterProviderOrder)
                .putBoolean("${prefix}openrouter_allow_fallbacks", profile.openRouterAllowFallbacks)
        }
        editor.apply()
    }

    fun loadProvider(vault: ApiKeyVault, agent: AgentKind? = null): ProviderProfile {
        val prefix = agent?.let(::providerPrefix)
        val hasAgentProfile = prefix != null && preferences.contains("${prefix}kind")
        val sourcePrefix = if (hasAgentProfile) prefix.orEmpty() else "provider_"
        val storedKind = runCatching {
            ProviderKind.valueOf(preferences.getString("${sourcePrefix}kind", null).orEmpty())
        }.getOrNull()
        val storedBaseUrl = storedKind?.let {
            preferences.getString("${sourcePrefix}base_url", it.defaultBaseUrl) ?: it.defaultBaseUrl
        }.orEmpty()
        val storedModel = storedKind?.let {
            preferences.getString("${sourcePrefix}model", it.defaultModel) ?: it.defaultModel
        }.orEmpty()
        // Older builds copied the global Claude/Anthropic default into a new
        // DeepSeek Harness profile. Treat that untouched, keyless placeholder
        // as unconfigured so DeepSeek opens on its own official provider.
        val legacyClaudeDefaultInDeepSeek = agent == AgentKind.DEEPSEEK_HARNESS &&
            storedKind == ProviderKind.ANTHROPIC &&
            !vault.contains(ProviderKind.ANTHROPIC.name) &&
            storedBaseUrl == ProviderKind.ANTHROPIC.defaultBaseUrl &&
            storedModel == ProviderKind.ANTHROPIC.defaultModel
        val kind = when {
            agent == null -> storedKind ?: ProviderKind.ANTHROPIC
            agent == AgentKind.DEEPSEEK_HARNESS && (!hasAgentProfile || legacyClaudeDefaultInDeepSeek) -> ProviderKind.DEEPSEEK
            storedKind != null && storedKind in providersForAgent(agent) -> storedKind
            agent == AgentKind.DEEPSEEK_HARNESS -> ProviderKind.DEEPSEEK
            else -> ProviderKind.ANTHROPIC
        }
        val useStoredValues = storedKind == kind
        val savedModel = if (useStoredValues) {
            preferences.getString("${sourcePrefix}model", kind.defaultModel) ?: kind.defaultModel
        } else {
            kind.defaultModel
        }
        // DeepSeek retired its legacy alias. Migrate only DeepSeek profiles so
        // custom and gateway providers keep their independently selected model.
        val model = if (
            kind == ProviderKind.DEEPSEEK &&
            savedModel in setOf("deepseek-chat", "deepseek-reasoner")
        ) {
            kind.defaultModel.also {
                preferences.edit().putString("${sourcePrefix}model", it).apply()
            }
        } else {
            savedModel
        }
        val resolvedProviderId = if (useStoredValues) {
            preferences.getString("${sourcePrefix}id", kind.name)?.takeIf(String::isNotBlank) ?: kind.name
        } else {
            kind.name
        }
        return ProviderProfile(
            kind = kind,
            baseUrl = if (useStoredValues) {
                preferences.getString("${sourcePrefix}base_url", kind.defaultBaseUrl) ?: kind.defaultBaseUrl
            } else {
                kind.defaultBaseUrl
            },
            model = model,
            hasSecret = vault.contains(resolvedProviderId),
            providerId = resolvedProviderId,
            dshApi = if (useStoredValues) {
                preferences.getString("${sourcePrefix}dsh_api", defaultDshApiForProvider(kind))
                    ?: defaultDshApiForProvider(kind)
            } else {
                defaultDshApiForProvider(kind)
            },
            openRouterProviderOrder = if (useStoredValues) {
                preferences.getString("${sourcePrefix}openrouter_order", "").orEmpty()
            } else {
                ""
            },
            openRouterAllowFallbacks = if (useStoredValues) {
                preferences.getBoolean("${sourcePrefix}openrouter_allow_fallbacks", true)
            } else {
                true
            },
        )
    }

    private fun providerPrefix(agent: AgentKind): String = "provider_${agent.stableId.replace('-', '_')}_"

    /**
     * Project the user last had open in the workspace. Persisted so a process killed in the
     * background (Doze / low-memory reclaim) can reopen the same project on the next cold start
     * instead of dropping the user back at the project list. Cleared only by [closeProject].
     */
    var lastProjectId: String?
        get() = preferences.getString("last_project_id", null)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove("last_project_id") else putString("last_project_id", value)
            }.apply()
        }

    /** Chat selected within [lastProjectId]; empty/absent restores the first (default) chat. */
    var lastChatId: String?
        get() = preferences.getString("last_chat_id", null)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove("last_chat_id") else putString("last_chat_id", value)
            }.apply()
        }

    fun saveProjects(projects: List<Project>) {
        val arr = JSONArray()
        projects.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
                put("language", p.language)
                put("slug", p.slug)
                put("rootPath", p.rootPath)
                put("updatedAtMillis", p.updatedAtMillis)
                put("kind", p.kind.name)
                put("type", p.type.name)
                p.androidTemplate?.let { put("androidTemplate", it.name) }
            })
        }
        preferences.edit().putString("projects_json", arr.toString()).apply()
    }

    fun loadProjects(): List<Project> {
        val raw = preferences.getString("projects_json", null) ?: return emptyList()
        var needsSave = false
        val list = runCatching {
            val arr = JSONArray(raw)
            val usedSlugs = mutableSetOf<String>()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val storedKind = obj.optString("kind", ProjectKind.PROJECT.name)
                if (!obj.has("kind") || storedKind == "QUICK_CHAT") needsSave = true
                val requestedSlug = obj.optString("slug").ifBlank { projectSlug(name) }
                var slug = requestedSlug
                if (!usedSlugs.add(slug)) {
                    slug = "$requestedSlug-${id.take(6)}"
                    var suffix = 2
                    while (!usedSlugs.add(slug)) slug = "$requestedSlug-${suffix++}"
                }
                if (slug != obj.optString("slug")) needsSave = true
                var millis = obj.optLong("updatedAtMillis", 0L)
                if (millis <= 0L) {
                    val workspaceDir = File(context.filesDir, "workspaces/$id")
                    millis = if (workspaceDir.exists() && workspaceDir.lastModified() > 0L) {
                        workspaceDir.lastModified()
                    } else {
                        System.currentTimeMillis() - 3600_000L
                    }
                    needsSave = true
                }
                Project(
                    id = id,
                    name = name,
                    description = obj.optString("description", ""),
                    language = obj.optString("language", ""),
                    slug = slug,
                    rootPath = obj.optString("rootPath", "").takeIf { root ->
                        root.isBlank() || (!root.startsWith('/') && !root.contains(".."))
                    } ?: "",
                    updatedAtMillis = millis,
                    kind = when (storedKind) {
                        "QUICK_CHAT" -> ProjectKind.QUICK_PROJECT
                        else -> runCatching { ProjectKind.valueOf(storedKind) }
                            .getOrDefault(ProjectKind.PROJECT)
                    },
                    type = runCatching {
                        ProjectType.valueOf(obj.optString("type", ProjectType.GENERAL.name))
                    }.getOrDefault(ProjectType.GENERAL),
                    androidTemplate = obj.optString("androidTemplate").takeIf(String::isNotBlank)?.let {
                        runCatching { AndroidTemplate.valueOf(it) }.getOrNull()
                    },
                )
            }
        }.getOrDefault(emptyList())

        if (needsSave && list.isNotEmpty()) {
            saveProjects(list)
        }
        return list
    }

    private val chatsDir = File(context.filesDir, "chats").also { it.mkdirs() }

    fun saveProjectChats(projectId: String, chats: List<ProjectChat>) {
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val arr = JSONArray()
        chats.forEach { chat ->
            arr.put(JSONObject().apply {
                put("id", chat.id)
                put("title", chat.title)
                put("createdAtMillis", chat.createdAtMillis)
                put("updatedAtMillis", chat.updatedAtMillis)
            })
        }
        File(projectDir, "index.json").writeText(arr.toString())
    }

    fun loadProjectChats(projectId: String): List<ProjectChat> {
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val index = File(projectDir, "index.json")
        if (index.exists()) {
            return runCatching {
                val arr = JSONArray(index.readText())
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ProjectChat(
                        id = obj.getString("id"),
                        title = obj.optString("title", "Chat"),
                        createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                        updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis()),
                    )
                }.sortedByDescending { it.updatedAtMillis }
            }.getOrDefault(emptyList())
        }

        // Migrate the original one-file-per-project conversation without losing it.
        val legacy = File(chatsDir, "$projectId.json")
        val legacyMessages = loadLegacyMessages(legacy)
        val now = System.currentTimeMillis()
        val chat = ProjectChat(
            id = "main",
            title = legacyMessages.firstOrNull { it.fromUser }?.text?.toChatTitle() ?: "Main chat",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        saveProjectChats(projectId, listOf(chat))
        if (legacyMessages.isNotEmpty()) saveMessages(projectId, chat.id, legacyMessages)
        return listOf(chat)
    }

    @Synchronized
    fun saveMessages(projectId: String, chatId: String, messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("fromUser", m.fromUser)
                put("text", m.text)
                put("createdAt", m.createdAt.toString())
                put("attachments", JSONArray().apply {
                    m.attachments.forEach { attachment ->
                        put(JSONObject().apply {
                            put("id", attachment.id)
                            put("displayName", attachment.displayName)
                            put("relativePath", attachment.relativePath)
                            put("mimeType", attachment.mimeType)
                            put("sizeBytes", attachment.sizeBytes)
                        })
                    }
                })
                put("workedMillis", m.workedMillis)
                put("workItems", JSONArray().apply {
                    m.workItems.forEach { item ->
                        put(JSONObject().apply {
                            put("title", item.title)
                            put("detail", item.detail)
                            put("isComplete", item.isComplete)
                            put("isCommand", item.isCommand)
                        })
                    }
                })
            })
        }
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val destination = File(projectDir, "$chatId.json")
        val temporary = File(projectDir, ".$chatId.json.tmp")
        temporary.writeText(arr.toString())
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    fun loadMessages(projectId: String, chatId: String): List<ChatMessage> {
        val file = File(File(chatsDir, projectId), "$chatId.json")
        return loadLegacyMessages(file)
    }

    fun deleteProjectChats(projectId: String) {
        File(chatsDir, projectId).deleteRecursively()
        File(chatsDir, "$projectId.json").delete()
    }

    private fun loadLegacyMessages(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ChatMessage(
                    id = obj.getString("id"),
                    fromUser = obj.getBoolean("fromUser"),
                    text = obj.getString("text"),
                    createdAt = runCatching { Instant.parse(obj.getString("createdAt")) }
                        .getOrDefault(Instant.now()),
                    attachments = obj.optJSONArray("attachments")?.let { attachments ->
                        (0 until attachments.length()).mapNotNull { index ->
                            runCatching {
                                attachments.getJSONObject(index).let { attachment ->
                                    ChatAttachment(
                                        id = attachment.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                                        displayName = attachment.getString("displayName"),
                                        relativePath = attachment.getString("relativePath"),
                                        mimeType = attachment.optString("mimeType", "application/octet-stream"),
                                        sizeBytes = attachment.optLong("sizeBytes", 0L),
                                    )
                                }
                            }.getOrNull()
                        }
                    }.orEmpty(),
                    workedMillis = obj.optLong("workedMillis", 0L),
                    workItems = obj.optJSONArray("workItems")?.let { workItems ->
                        (0 until workItems.length()).mapNotNull { index ->
                            runCatching {
                                workItems.getJSONObject(index).let { item ->
                                    com.mobileharness.code.model.ActivityItem(
                                        title = item.optString("title"),
                                        detail = item.optString("detail"),
                                        isComplete = item.optBoolean("isComplete", true),
                                        isCommand = item.optBoolean("isCommand", false),
                                    )
                                }
                            }.getOrNull()
                        }
                    }.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Opt-in flags for the privileged "Phone Control" routes (all default off, fail-closed). */
    var privilegedShizukuOptIn: Boolean
        get() = preferences.getBoolean("privileged_shizuku_opt_in", false)
        set(value) { preferences.edit().putBoolean("privileged_shizuku_opt_in", value).apply() }

    var privilegedWirelessAdbOptIn: Boolean
        get() = preferences.getBoolean("privileged_wireless_adb_opt_in", false)
        set(value) { preferences.edit().putBoolean("privileged_wireless_adb_opt_in", value).apply() }

    /** "host:port" endpoint for Wireless Debugging (empty when not configured). */
    var wirelessAdbEndpoint: String
        get() = preferences.getString("wireless_adb_endpoint", "").orEmpty()
        set(value) { preferences.edit().putString("wireless_adb_endpoint", value.trim()).apply() }

    var privilegedRootOptIn: Boolean
        get() = preferences.getBoolean("privileged_root_opt_in", false)
        set(value) { preferences.edit().putBoolean("privileged_root_opt_in", value).apply() }

    fun privilegedOptIn(route: com.mobileharness.code.device.PrivilegeRoute): Boolean = when (route) {
        com.mobileharness.code.device.PrivilegeRoute.SHIZUKU -> privilegedShizukuOptIn
        com.mobileharness.code.device.PrivilegeRoute.WIRELESS_ADB -> privilegedWirelessAdbOptIn
        com.mobileharness.code.device.PrivilegeRoute.ROOT -> privilegedRootOptIn
    }

    fun setPrivilegedOptIn(route: com.mobileharness.code.device.PrivilegeRoute, enabled: Boolean) {
        when (route) {
            com.mobileharness.code.device.PrivilegeRoute.SHIZUKU -> privilegedShizukuOptIn = enabled
            com.mobileharness.code.device.PrivilegeRoute.WIRELESS_ADB -> privilegedWirelessAdbOptIn = enabled
            com.mobileharness.code.device.PrivilegeRoute.ROOT -> privilegedRootOptIn = enabled
        }
    }

    private fun String.toChatTitle(): String {
        val clean = replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= 42) clean else clean.take(39).trimEnd() + "…"
    }

    companion object {
        /** Install and update state that describes this phone, not the user's work. */
        val DEVICE_ONLY_KEYS = setOf(
            "runtime_setup_complete",
            "background_setup_complete",
            "last_app_update_check_millis",
            "debug_update_manifest_url",
            "test_provider_defaults_version",
            "dsh_version",
            "whats_new_seen_code",
            "backup_feature_seen",
            "mcp_servers_json",
            "ssh_profiles_json",
            "invite_code",
            "redeemed_invite_code",
            "invite_share_count",
            "privileged_shizuku_opt_in",
            "privileged_wireless_adb_opt_in",
            "wireless_adb_endpoint",
            "privileged_root_opt_in",
            "skills_json",
            "installed_skills_json",
            "installed_plugins_json",
            "hook_rules_json",
            "workspace_groups_json",
            "workspace_grants_json",
            "lsp_servers_json",
            "testing_history_json",
        )
    }
}
