package com.mobileharness.code.ui

/**
 * Pure, JVM-testable model of the application's foreground/background lifecycle.
 *
 * Android does not give a ViewModel a reliable "process went to background" callback, yet the
 * most fragile long-lived resources (SSH tunnels, interactive PTY sessions, audio capture) can
 * silently die while the process is backgrounded (Doze, network drop, memory reclaim). This
 * class turns the Activity's onStart/onStop edges into a deterministic resume decision so the
 * UI can fail closed: it never auto-reconnects or re-sends credentials, it only refreshes live
 * status so a stale "connected" never lies to the user.
 */
class SessionResumePolicy(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    enum class Phase { FOREGROUND, BACKGROUND }

    /** What the app should refresh after returning to the foreground. */
    data class ResumeDecision(
        val refreshSshStatus: Boolean,
        val refreshDiagnostics: Boolean,
        val backgroundedDurationMillis: Long,
    )

    private var phase = Phase.FOREGROUND
    private var backgroundedAtMillis: Long? = null

    val isBackgrounded: Boolean get() = phase == Phase.BACKGROUND

    /** Idempotent: repeated background transitions collapse into one timestamp. */
    fun onBackground() {
        if (phase == Phase.BACKGROUND) return
        phase = Phase.BACKGROUND
        backgroundedAtMillis = nowMillis()
    }

    /** Idempotent: the first resume after a background carries the full decision, later ones are no-ops. */
    fun onForeground(): ResumeDecision {
        // coerceAtLeast(0) guards against manual/NTP clock adjustments while backgrounded.
        val duration = backgroundedAtMillis?.let { (nowMillis() - it).coerceAtLeast(0L) } ?: 0L
        val wasBackground = phase == Phase.BACKGROUND
        phase = Phase.FOREGROUND
        backgroundedAtMillis = null
        return ResumeDecision(
            refreshSshStatus = wasBackground,
            refreshDiagnostics = wasBackground && duration >= DIAGNOSTICS_REFRESH_THRESHOLD_MS,
            backgroundedDurationMillis = duration,
        )
    }

    companion object {
        /**
         * Below this background duration a cheap SSH-status refresh is enough; longer absences
         * justify a full diagnostics refresh (CPU/memory/latency drift while backgrounded).
         */
        const val DIAGNOSTICS_REFRESH_THRESHOLD_MS = 30_000L
    }
}
