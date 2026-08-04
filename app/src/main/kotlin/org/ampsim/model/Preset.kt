package org.ampsim.model

import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class Preset(
    val version: String = "1.0",
    val metadata: PresetMetadata,
    val chain: Chain
) {
    companion object {
        fun create(
            name: String,
            description: String = "",
            effectUnits: List<EffectUnit> = emptyList(),
            author: String? = null,
            tags: List<String> = emptyList()
        ) = Preset(
            metadata = PresetMetadata(
                name = name,
                description = description,
                author = author,
                tags = tags
            ),
            chain = Chain(effectUnits)
        )
    }

    fun withUpdatedChain(block: Chain.() -> Chain): Preset =
        copy(chain = chain.block(), metadata = metadata.copy(modified = Clock.System.now()))
}