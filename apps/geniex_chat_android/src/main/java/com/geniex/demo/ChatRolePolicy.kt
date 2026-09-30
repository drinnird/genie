package com.geniex.demo

/**
 * Guards GenieX chat-template calls from malformed role sequences.
 *
 * Several llama.cpp/Jinja templates require an optional leading `system`
 * message followed by strictly alternating `user`/`assistant` turns. Some
 * native template implementations abort the process instead of returning a
 * recoverable error when that invariant is violated, so validate in Kotlin
 * before crossing JNI.
 */
internal object ChatRolePolicy {
    private val supportedRoles = setOf("system", "user", "assistant")

    fun normalize(role: String): String = role.trim().lowercase()

    fun validateForGeneration(roles: List<String>): String? {
        if (roles.isEmpty()) return "Chat messages cannot be empty."

        val normalized = roles.map(::normalize)
        val unsupported = normalized.firstOrNull { it !in supportedRoles }
        if (unsupported != null) {
            return "Unsupported chat role '$unsupported'. Supported roles are system, user, and assistant."
        }

        var index = 0
        if (normalized.first() == "system") index = 1
        if (index >= normalized.size) return "A system message must be followed by a user message."

        var expected = "user"
        while (index < normalized.size) {
            val actual = normalized[index]
            if (actual == "system") return "The system role is only allowed as the first message."
            if (actual != expected) {
                return "Invalid chat role order at message ${index + 1}: expected $expected but found $actual."
            }
            expected = if (expected == "user") "assistant" else "user"
            index++
        }

        if (normalized.last() != "user") {
            return "A generation request must end with a user message."
        }
        return null
    }

    /**
     * Number of leading messages to remove to discard one complete oldest turn.
     * The caller should pass conversation roles without a leading system role.
     */
    fun oldestTurnPrefixCount(roles: List<String>, preserveLatestUser: Boolean = true): Int {
        if (roles.isEmpty()) return 0
        val normalized = roles.map(::normalize)
        val minimumRemaining = if (preserveLatestUser && normalized.last() == "user") 1 else 0
        if (normalized.size <= minimumRemaining) return 0
        return if (normalized.first() == "user" &&
            normalized.size - minimumRemaining >= 2 &&
            normalized.getOrNull(1) == "assistant"
        ) {
            2
        } else {
            1
        }
    }
}
