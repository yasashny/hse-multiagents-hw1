package com.yasashny.agentshw1.state.models

import kotlinx.serialization.Serializable

@Serializable
data class PlanStep(
    val id: String,
    val description: String,
    val tools: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
)
