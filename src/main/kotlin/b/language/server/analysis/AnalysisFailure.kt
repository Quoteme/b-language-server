package b.language.server.analysis

/** Why an analysis could not run at all, as opposed to the problems it finds in a model. */
interface AnalysisFailure {
    val message: String
}
