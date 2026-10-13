package com.yasashny.agentshw1.policy

sealed interface Decision {
    data object Allow : Decision
    data class Deny(val reason: String) : Decision
}
