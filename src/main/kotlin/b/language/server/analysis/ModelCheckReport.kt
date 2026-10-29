package b.language.server.analysis

/** The problems found by model checking, and a summary of the run. */
data class ModelCheckReport(
    val problems: List<Problem>,
    val summary: String,
)
