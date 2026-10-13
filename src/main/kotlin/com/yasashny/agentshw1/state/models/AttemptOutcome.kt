package com.yasashny.agentshw1.state.models

enum class AttemptOutcome {
    RUNNING,
    OK,
    ERROR,
    DENIED_POLICY,
    USER_DENIED,
    CONFLICT,
    FAILED_BY_USER,
    PROTOCOL_ERROR,
    RESOLVED_RETRY,
}
