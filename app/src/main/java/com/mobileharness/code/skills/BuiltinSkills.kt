package com.mobileharness.code.skills

/**
 * Curated, read-only default skills shipped with the app. They are pure instruction fragments
 * (inspired by permissively-licensed agent "skill" patterns) and are all disabled by default; the
 * user opts into them individually. They never contain code, credentials or network endpoints.
 */
object BuiltinSkills {
    fun defaults(): List<Skill> = listOf(
        Skill(
            id = "code_review",
            name = "Code review",
            description = "Focus answers on correctness, readability and security issues in code.",
            instructions = """
                When the user asks about code, structure the answer as a short review:
                1. Correctness — state any bugs or edge cases you see.
                2. Readability — note confusing names, duplicated logic, or missing structure.
                3. Safety — flag unsafe input handling, unbounded reads/loops, and secret leakage.
                Be concrete and quote the relevant identifiers; do not invent code that is not shown.
            """.trimIndent(),
        ),
        Skill(
            id = "android_app_audit",
            name = "Android app audit",
            description = "Audit guidance for on-device Android apps (permissions, lifecycle, storage).",
            instructions = """
                For Android app questions, prefer these checks:
                - Permissions: are only needed permissions requested, with runtime gating?
                - Lifecycle: are coroutines/processes/listeners cancelled or released on stop/destroy?
                - Storage: are secrets in Keystore/EncryptedSharedPreferences, excluded from backup?
                - Memory: are large buffers/bitmaps bounded and recycled?
                Present findings as a concise checklist with the risk level of each item.
            """.trimIndent(),
        ),
        Skill(
            id = "git_commit_message",
            name = "Commit messages",
            description = "Draft concise, conventional commit messages from described changes.",
            instructions = """
                When the user describes changes and asks for a commit message, reply with a single
                conventional-commit subject line (type(scope): summary, imperative mood, <= 72 chars)
                followed by a short body of 1-3 bullets explaining the "what" and "why". Do not add
                a body when a one-liner is enough.
            """.trimIndent(),
        ),
        Skill(
            id = "explain_like_i_am_five",
            name = "Explain simply",
            description = "Answer technical questions in plain, beginner-friendly language.",
            instructions = """
                Prefer short sentences and everyday analogies. Define any technical term the first
                time it appears, and end with a one-sentence summary the user can repeat back.
            """.trimIndent(),
        ),
    )
}
