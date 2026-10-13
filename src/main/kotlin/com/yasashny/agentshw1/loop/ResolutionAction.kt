package com.yasashny.agentshw1.loop

enum class ResolutionAction {
    RETRY,
    FAIL,
    ABORT;

    companion object {
        fun parse(value: String): ResolutionAction =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown resolution '$value' (use retry|fail|abort)")
    }
}
