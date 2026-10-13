package com.yasashny.agentshw1.state.models

import kotlinx.serialization.Serializable

@Serializable
data class FileEntry(
    val path: String,
    val existedBefore: Boolean,
    val originalHash: String? = null,
    val originalContentB64: String? = null,
    val finalHash: String? = null,
    val finalContentB64: String? = null,
)
