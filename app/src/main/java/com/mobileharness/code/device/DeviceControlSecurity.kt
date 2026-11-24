package com.mobileharness.code.device

/**
 * Session-scoped approval state for device control, keyed by risk. It implements the rule:
 *
 * - READ      → never needs confirmation (a safe, read-only query),
 * - CONTROL   → one confirmation per session,
 * - DESTRUCTIVE → confirmation every single time (never "remembered").
 *
 * This is deliberately independent of [PrivilegePolicy] (which gates the *transport*: Shizuku /
 * root / wireless ADB opt-in). Both gates must pass before any command runs.
 */
class DeviceApprovalState {
    private var controlApproved = false

    /** Whether [risk] still requires user confirmation in this session. */
    fun requiresConfirmation(risk: DeviceCommandRisk): Boolean = when (risk) {
        DeviceCommandRisk.READ -> false
        DeviceCommandRisk.CONTROL -> !controlApproved
        DeviceCommandRisk.DESTRUCTIVE -> true
    }

    /** Records an approval. CONTROL is remembered for the session; DESTRUCTIVE is never remembered. */
    fun approve(risk: DeviceCommandRisk) {
        if (risk == DeviceCommandRisk.CONTROL) controlApproved = true
    }

    /** Resets the session-scoped CONTROL approval (e.g. new chat / explicit reset). */
    fun reset() {
        controlApproved = false
    }

    /** Whether CONTROL has already been approved this session. */
    fun controlApproved(): Boolean = controlApproved
}

/** Outcome recorded to the audit log for a single device command attempt. */
enum class DeviceCommandOutcome { ALLOWED, DENIED, SUCCESS, ERROR, TIMEOUT }

/**
 * An append-only, bounded audit trail of device command decisions and executions. It records the
 * command text, its classified risk, the route it took, the outcome, and a timestamp — but never
 * any secrets (no API keys, no auth tokens). Pure and JVM-testable.
 */
data class DeviceAuditEntry(
    val command: String,
    val risk: DeviceCommandRisk,
    val route: String,
    val outcome: DeviceCommandOutcome,
    val reason: String = "",
    val timestampMillis: Long,
)

class DeviceAuditLog(private val maxEntries: Int = 500) {
    private val entries = ArrayDeque<DeviceAuditEntry>()

    @Synchronized
    fun record(entry: DeviceAuditEntry) {
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
    }

    @Synchronized
    fun entries(): List<DeviceAuditEntry> = entries.toList()

    @Synchronized
    fun recent(limit: Int): List<DeviceAuditEntry> = entries.takeLast(limit.coerceAtLeast(0))

    @Synchronized
    fun byRisk(risk: DeviceCommandRisk): List<DeviceAuditEntry> = entries.filter { it.risk == risk }

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * The central security gate for device commands. It combines:
 *  1. risk classification ([DeviceCommandRisk]) → confirmation requirement,
 *  2. the session [DeviceApprovalState] → remembered CONTROL approvals,
 * and produces a single, auditable decision. It never bypasses [PrivilegePolicy]; it only decides
 * whether a *user confirmation* is additionally required before the policy runs.
 */
object DeviceControlSecurity {
    sealed interface Decision {
        /** Run immediately (READ, or CONTROL already approved this session). */
        data class Run(val risk: DeviceCommandRisk) : Decision

        /** Requires user confirmation; the command must not run yet. */
        data class Confirm(val risk: DeviceCommandRisk) : Decision
    }

    fun evaluate(command: String, approval: DeviceApprovalState): Decision {
        val risk = DeviceCommandRisk.of(command)
        return if (approval.requiresConfirmation(risk)) Decision.Confirm(risk) else Decision.Run(risk)
    }

    /**
     * Records an audit entry after a decision/execution. [outcome] reflects what actually happened.
     */
    fun audit(log: DeviceAuditLog, command: String, route: String, outcome: DeviceCommandOutcome, reason: String = "") {
        log.record(
            DeviceAuditEntry(
                command = command.take(512),
                risk = DeviceCommandRisk.of(command),
                route = route,
                outcome = outcome,
                reason = reason.take(256),
                timestampMillis = System.currentTimeMillis(),
            ),
        )
    }
}
