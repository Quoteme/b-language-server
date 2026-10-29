package b.language.server.analysis

import b.language.server.analysis.documentation.LineText
import b.language.server.project.Project
import b.language.server.project.SourcePosition

/** A completion candidate whose documentation is only rendered if it is offered. */
class LazyCandidate(
    val word: String,
    val kind: CandidateKind,
    val documentation: () -> Markdown?,
)

/** Completes the word left of the cursor with keywords, built-ins and identifiers starting with it. */
abstract class WordCompletion<in P : Project> : CompletionProvider<P> {
    final override fun complete(
        project: P,
        at: SourcePosition,
    ): List<CompletionCandidate> {
        val prefix = (LineText.of(project.text, at.line) ?: LineText("")).prefixBefore(at.column)
        if (prefix.isEmpty()) return emptyList()
        return candidates(project, at)
            .filter { it.word.all(LineText::isWordChar) && it.word.startsWith(prefix, ignoreCase = true) }
            .distinctBy { it.word }
            .map { CompletionCandidate(it.word, it.kind, it.documentation()) }
            .toList()
    }

    /** Every word that could be written at [at], in the order to offer them. */
    protected abstract fun candidates(
        project: P,
        at: SourcePosition,
    ): Sequence<LazyCandidate>

    protected companion object {
        /** Reserved words of B are upper case, such as INTEGER or INVARIANT; the others are operators such as card. */
        fun builtin(word: String) = if (word.any(Char::isLowerCase)) CandidateKind.FUNCTION else CandidateKind.KEYWORD
    }
}
