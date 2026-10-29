package b.language.server.analysis.prob

import b.language.server.config.Settings

/** Preferences of ProB, set on a state space before a model is loaded into it. */
data class ProBPreferences(
    val strictClashChecking: Boolean? = null,
    val typeCheckDefinitions: Boolean? = null,
    val performanceInfo: Boolean? = null,
    val timeoutMillis: Int? = null,
    val maxOperations: Int? = null,
    val maxInitialisations: Int? = null,
) {
    /** The preferences as ProB names them. */
    fun toProB(): Map<String, String> {
        fun flag(value: Boolean) = if (value) "TRUE" else "FALSE"
        return buildMap {
            strictClashChecking?.let { put("STRICT_CLASH_CHECKING", flag(it)) }
            typeCheckDefinitions?.let { put("TYPE_CHECK_DEFINITIONS", flag(it)) }
            performanceInfo?.let { put("PERFORMANCE_INFO", flag(it)) }
            timeoutMillis?.let { put("TIME_OUT", it.toString()) }
            maxOperations?.let { put("MAX_OPERATIONS", it.toString()) }
            maxInitialisations?.let { put("MAX_INITIALISATIONS", it.toString()) }
        }
    }

    companion object {
        /** The preferences of checking a model under the user's [settings]. */
        fun checking(settings: Settings) =
            ProBPreferences(
                strictClashChecking = settings.strictChecks,
                typeCheckDefinitions = settings.strictChecks,
                performanceInfo = settings.performanceHints,
            )
    }
}
