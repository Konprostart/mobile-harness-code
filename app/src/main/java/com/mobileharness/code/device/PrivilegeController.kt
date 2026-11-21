package com.mobileharness.code.device

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Thin Android orchestrator that glues [PrivilegePolicy] to the concrete executors and the
 * process-scoped [PrivilegeEventLog]. It is intentionally small: all decisions live in the pure
 * [PrivilegePolicy], so the controller can be swapped or tested without re-deriving the rules.
 */
object PrivilegeController {

    private val executors: Map<PrivilegeRoute, PrivilegeExecutor> = mapOf(
        PrivilegeRoute.ROOT to SuRootExecutor,
        PrivilegeRoute.SHIZUKU to ShizukuExecutor,
        // Executes once the app has a paired ADB key + a configured endpoint (see WirelessAdbStore).
        PrivilegeRoute.WIRELESS_ADB to WirelessAdbExecutor,
    )

    /** Re-runs route detection and records a DETECTED event per route (without secrets). */
    fun detect(context: Context): List<PrivilegeRouteState> {
        val states = PrivilegeDetector.detect(context)
        states.forEach { state ->
            PrivilegeEventLog.record(state.route, PrivilegeStage.DETECTED, state.detail)
        }
        return states
    }

    /**
     * Runs [request] through the fail-closed policy and, if allowed, the matching executor.
     * Never throws; a missing/invalid route or a transport failure becomes a clean result.
     */
    fun execute(request: PrivilegeRequest, state: PrivilegeRouteState, optIn: Boolean): PrivilegeResult {
        return when (val decision = PrivilegePolicy.decide(request, state, optIn)) {
            is PrivilegePolicy.Decision.Denied -> {
                PrivilegeEventLog.record(request.route, PrivilegeStage.DENIED, decision.reason.name)
                PrivilegeResult.denied(decision.reason.name)
            }
            is PrivilegePolicy.Decision.Allowed -> {
                val executor = executors[request.route]
                if (executor == null) {
                    PrivilegeEventLog.record(request.route, PrivilegeStage.ERROR, "no executor for route")
                    return PrivilegeResult.denied("no executor for route")
                }
                PrivilegeEventLog.record(request.route, PrivilegeStage.EXEC, null)
                val result = executor.execute(request, decision.timeoutMillis, decision.maxOutputBytes)
                when (result.outcome) {
                    PrivilegeOutcome.SUCCESS -> PrivilegeEventLog.record(request.route, PrivilegeStage.SUCCESS, null)
                    PrivilegeOutcome.TIMEOUT -> PrivilegeEventLog.record(request.route, PrivilegeStage.TIMEOUT, null)
                    PrivilegeOutcome.DENIED -> PrivilegeEventLog.record(request.route, PrivilegeStage.DENIED, result.error)
                    PrivilegeOutcome.ERROR -> PrivilegeEventLog.record(request.route, PrivilegeStage.ERROR, result.error)
                }
                result
            }
        }
    }

    /**
     * Requests Shizuku permission for this app. [onResult] is invoked with whether the grant was
     * accepted; the Shizuku manager shows the confirmation dialog.
     */
    fun requestShizukuPermission(onResult: (Boolean) -> Unit) {
        PrivilegeEventLog.record(PrivilegeRoute.SHIZUKU, PrivilegeStage.PERMISSION_REQUESTED, null)
        Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeRequestPermissionResultListener(this)
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                PrivilegeEventLog.record(
                    PrivilegeRoute.SHIZUKU,
                    if (granted) PrivilegeStage.PERMISSION_GRANTED else PrivilegeStage.PERMISSION_DENIED,
                    null,
                )
                onResult(granted)
            }
        })
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure {
                PrivilegeEventLog.record(PrivilegeRoute.SHIZUKU, PrivilegeStage.ERROR, it.message)
                onResult(false)
            }
    }

    fun recordOptInChange(route: PrivilegeRoute, enabled: Boolean) {
        PrivilegeEventLog.record(route, PrivilegeStage.OPT_IN_CHANGED, if (enabled) "enabled" else "disabled")
    }

    private const val REQUEST_CODE = 1001
}
