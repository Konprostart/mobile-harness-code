package com.mobileharness.code.permission

import android.content.Context
import java.io.File

/**
 * Android-facing orchestrator for the unified permission system. Holds the persistent
 * [PermissionStore], the [PermissionAuditLog] and the global [EmergencyStop], and exposes a single
 * [decide] entry point that every agent/tool integration should call. All decision logic stays in
 * the pure [PermissionPolicy]; this object only owns persistence and wiring.
 */
object PermissionController {
    @Volatile
    private var store: PermissionStore? = null

    val auditLog = PermissionAuditLog()

    val emergencyStop = EmergencyStop()

    /** Idempotent; loads persisted rules and arms the persistence sink. */
    @Synchronized
    fun initialize(context: Context) {
        if (store != null) return
        val file = File(context.filesDir, "permission_rules.json")
        val s = PermissionStore { json -> runCatching { file.writeText(json) } }
        if (file.isFile) {
            runCatching { s.load(file.readText()) }
        }
        store = s
    }

    /** Resolves a request against the current rule set + emergency-stop state, and audits it. */
    fun decide(request: PermissionRequest): PermissionDecision {
        val s = store ?: PermissionStore()
        val decision = PermissionPolicy.decide(request, s.snapshot(), emergencyStop.toContext())
        auditLog.record(request, decision)
        if (decision is PermissionDecision.Allowed && decision.ruleId != null) {
            // One-shot grants are consumed on use.
            runCatching { s.consumeAllowOnce(decision.ruleId) }
        }
        return decision
    }

    fun upsert(rule: PermissionRule) {
        store?.upsert(rule)
    }

    fun remove(id: String): Boolean = store?.remove(id) ?: false

    fun snapshot(): List<PermissionRule> = store?.snapshot() ?: emptyList()

    /** Call when a session ends to drop ALLOW_SESSION grants. */
    fun clearSessionScoped() {
        store?.clearSessionScoped()
    }

    fun clear() {
        store?.clear()
    }

    /** True when the runtime has been initialized (mainly for tests). */
    fun isInitialized(): Boolean = store != null
}
