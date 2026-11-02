package b.language.server.config

import arrow.core.Either
import com.google.gson.JsonObject
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

/** Settings the client can configure for the language server. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Settings(
    /** Runs ProB's extended static checks (linting) on top of type checking. */
    val strictChecks: Boolean = true,
    /**
     * Enables well-definedness checks, e.g. division by zero or function application outside the domain.
     * See [Well-Definedness Checking](https://prob.hhu.de/w/index.php?title=Well-Definedness_Checking).
     */
    val wdChecks: Boolean = true,
    /** Reports hints about constructs that ProB may evaluate inefficiently. */
    val performanceHints: Boolean = true,
    /**
     * Path to the ProB installation to use; `"DEFAULT"` uses the probcli bundled with the ProB Java Kernel.
     * See [ProB Java API](https://prob.hhu.de/w/index.php?title=ProB_Java_API).
     */
    @JsonNames("proBHome") val probHome: String = "DEFAULT",
    /** Sends verbose log messages from the server to the client's output channel. */
    val debugMode: Boolean = true,
    /** Time limit in milliseconds for discharging each Event-B proof obligation. */
    val poTimeout: Int = 1000,
    /** Maximum number of states explored by the model checker. */
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

        /** Reads the settings the client sent as JSON; values of the wrong kind make them invalid. */
        fun from(json: JsonObject?): Either<InvalidSettings, Settings> =
            Either
                .catchOrThrow<SerializationException, Settings> { format.decodeFromString(json?.toString() ?: "{}") }
                .mapLeft { InvalidSettings(it.message ?: "invalid settings") }
    }
}

/** Settings the client sent that cannot be read, and why. */
data class InvalidSettings(
    val message: String,
)
