package com.mobileharness.code.device

/** Terminal outcome of a privileged execution attempt. */
enum class PrivilegeOutcome {
    SUCCESS,
    TIMEOUT,
    DENIED,
    ERROR,
}

/**
 * Result of one privileged command. Output is bounded by [PrivilegePolicy] and never contains
 * credentials; [error] holds a short, non-sensitive explanation for non-success outcomes.
 */
data class PrivilegeResult(
    val outcome: PrivilegeOutcome,
    val exitCode: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
) {
    companion object {
        fun denied(reason: String): PrivilegeResult =
            PrivilegeResult(outcome = PrivilegeOutcome.DENIED, error = reason)

        fun error(reason: String): PrivilegeResult =
            PrivilegeResult(outcome = PrivilegeOutcome.ERROR, error = reason)
    }
}
