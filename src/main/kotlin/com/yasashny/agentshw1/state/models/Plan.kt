package com.yasashny.agentshw1.state.models

import kotlinx.serialization.Serializable

@Serializable
data class Plan(
    val goal: String,
    val steps: List<PlanStep>,
    val risks: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val doneCriterion: String = "",
)
