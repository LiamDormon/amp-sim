package org.ampsim.model

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class ConfigurationProfileMetadata(
    val name: String,
    val description: String = "",
    val created: Instant = Clock.System.now(),
    val modified: Instant = Clock.System.now()
)
