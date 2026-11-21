package com.mobileharness.code.permission

/**
 * Global emergency stop. When engaged, [PermissionPolicy] denies every request regardless of rules,
 * giving the user a single kill-switch that cuts across all agents, tools and integrations.
 * Thread-safe and observable so the UI can reflect the state immediately.
 */
class EmergencyStop {
    @Volatile
    var engaged: Boolean = false
        private set

    @Volatile
    private var listener: ((Boolean) -> Unit)? = null

    fun engage() {
        engaged = true
        listener?.invoke(true)
    }

    fun disengage() {
        engaged = false
        listener?.invoke(false)
    }

    fun observe(listener: ((Boolean) -> Unit)?) {
        this.listener = listener
    }

    /** Convenience: a [PermissionPolicy.Context] reflecting the current stop state. */
    fun toContext(): PermissionPolicy.Context = PermissionPolicy.Context(emergencyStopped = engaged)
}
