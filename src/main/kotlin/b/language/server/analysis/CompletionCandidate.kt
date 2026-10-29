package b.language.server.analysis

enum class CandidateKind { KEYWORD, FUNCTION, CLASS, ENUM, ENUM_MEMBER, CONSTANT, VARIABLE, EVENT, VALUE }

/** A word that completes what the user is typing, with its documentation. */
data class CompletionCandidate(
    val word: String,
    val kind: CandidateKind,
    val documentation: Markdown?,
)
