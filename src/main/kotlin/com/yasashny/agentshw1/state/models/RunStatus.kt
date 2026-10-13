package com.yasashny.agentshw1.state.models

enum class RunStatus {
    RUNNING,
    AWAITING_CONFIRMATION,
    AWAITING_PLAN_APPROVAL,
    FINISHED,
    ABORTED_BY_USER,
    PLAN_REJECTED,
    BUDGET_EXCEEDED,
    FAILED,
    ROLLED_BACK,
}
