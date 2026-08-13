package org.ampsim.model

import kotlinx.serialization.Serializable

/**
 * A named, persistable snapshot of the app's full [AppConfiguration] — lets a
 * user save a tuned setup (audio devices, real-time settings, ...) and
 * restore it later. Stored separately from the live config file; see
 * [org.ampsim.persistence.FileSystemProfileRepository].
 */
@Serializable
data class ConfigurationProfile(
    val version: String = "1.0",
    val metadata: ConfigurationProfileMetadata,
    val configuration: AppConfiguration
) {
    companion object {
        fun create(
            name: String,
            description: String = "",
            configuration: AppConfiguration
        ) = ConfigurationProfile(
            metadata = ConfigurationProfileMetadata(name = name, description = description),
            configuration = configuration
        )
    }
}
