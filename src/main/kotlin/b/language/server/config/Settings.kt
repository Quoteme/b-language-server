package b.language.server.config

data class Settings(
    val strictChecks: Boolean = true,
    val wdChecks: Boolean = true,
    val performanceHints: Boolean = true,
    val probHome: String = "DEFAULT",
    val debugMode: Boolean = true,
    val poTimeout: Int = 1000,
    val modelCheckStates: Int = 100000,
)
