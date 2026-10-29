package b.language.server.config

import com.google.gson.JsonObject
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Settings(
    val strictChecks: Boolean = true,
    val wdChecks: Boolean = true,
    val performanceHints: Boolean = true,
    @JsonNames("proBHome") val probHome: String = "DEFAULT",
    val debugMode: Boolean = true,
    val poTimeout: Int = 1000,
    val modelCheckStates: Int = 100000,
) {
    companion object {
        // Missing, null and unknown keys fall back to the defaults instead of failing the settings future.
        private val format =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }

        /** Reads the settings the client sent as JSON. */
        fun from(json: JsonObject?): Settings = format.decodeFromString(json?.toString() ?: "{}")
    }
}
