package com.yasashny.agentshw1.state.models

import kotlinx.serialization.Serializable

@Serializable
data class JournalState(
    val lastSnapshot: Map<String, String> = emptyMap(),
    val lastSnapshotContents: Map<String, String> = emptyMap(),
    val entries: List<FileEntry> = emptyList(),
    val conflicts: List<String> = emptyList(),
)
