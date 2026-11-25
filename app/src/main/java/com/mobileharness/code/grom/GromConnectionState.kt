package com.mobileharness.code.grom

import app.grom.sdk.connect.GromService

/**
 * The connection phase the UI renders. A lamp is derived only from this measured
 * state — never from the ON/OFF switch alone (S6-R025 applied to the client).
 */
enum class GromPhase {
    OFF,
    CONNECTING,
    CONNECTED,
    AUTH_REQUIRED,
    DISCONNECTED,
    INCOMPATIBLE,
    ERROR,
}

/**
 * A point-in-time snapshot of the GROM connection, safe to render. It carries the
 * measured facts from the last discovery/authenticated call and nothing more.
 */
data class GromConnectionSnapshot(
    val phase: GromPhase,
    val baseUrl: String = "",
    val enabled: Boolean = false,
    val serverVersion: String? = null,
    val apiVersion: Int? = null,
    val schema: String? = null,
    val principal: String? = null,
    val capabilities: List<String> = emptyList(),
    val services: List<GromService> = emptyList(),
    val authMethods: List<String> = emptyList(),
    val message: String? = null,
)
