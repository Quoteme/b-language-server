package b.language.server.config

data class ProBSettings(
    val wdChecks: Boolean,
    val strictChecks: Boolean,
    val performanceHints: Boolean,
    val poTimeout: Int = 1000,
    val modelCheckStates: Int = 100000,
)
