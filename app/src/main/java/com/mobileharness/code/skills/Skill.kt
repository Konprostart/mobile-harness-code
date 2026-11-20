package com.mobileharness.code.skills

/**
 * A local, curated "skill": a named block of assistant instructions (a reusable prompt fragment)
 * that the user can opt into. Skills are pure text and are only ever injected into the assistant's
 * system prompt — the skill system never downloads or executes code, and a skill can never reach
 * the network, filesystem or an MCP tool on its own.
 *
 * [enabled] is the per-skill opt-in. A disabled skill is stored but never injected.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val enabled: Boolean = false,
)
