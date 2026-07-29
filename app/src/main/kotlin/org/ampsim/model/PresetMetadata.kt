package org.ampsim.model

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class PresetMetadata(
    val name: String,
    val description: String = "",
    val created: Instant = Clock.System.now(),
    val modified: Instant = Clock.System.now(),
    val tags: List<String> = emptyList(),
    val author: String? = null
)
