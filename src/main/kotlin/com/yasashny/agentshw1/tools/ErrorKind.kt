package com.yasashny.agentshw1.tools

enum class ErrorKind(val wire: String) {
    ERROR("error"),
    PATH_DENIED("path_denied"),
    DENIED_BY_POLICY("denied_by_policy"),
    PROTOCOL("protocol"),
    PROTOCOL_ERROR("protocol_error"),
    CONFLICT("conflict"),
    USER_DENIED("user_denied"),
    INTERRUPTED("interrupted"),
    INVALID_PLAN("invalid_plan"),
    TASK_NOT_COMPLETED("task_not_completed"),
    FAILED_AFTER_CRASH("failed_after_crash"),
    SKIPPED("skipped"),
}
