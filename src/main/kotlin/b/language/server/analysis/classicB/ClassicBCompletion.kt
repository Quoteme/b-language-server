package b.language.server.analysis.classicB

import b.language.server.analysis.CandidateKind
import b.language.server.analysis.LastCheck
import b.language.server.analysis.LazyCandidate
import b.language.server.analysis.WordCompletion
import b.language.server.analysis.documentation.CodeLanguage
import b.language.server.analysis.documentation.DocumentationTables
import b.language.server.project.SourcePosition
import b.language.server.project.classicB.ClassicBProject

/** Completes classical B keywords, operators, and the machine's identifiers from the last check. */
class ClassicBCompletion(
    private val identifiers: LastCheck<IdentifierCatalog>,
) : WordCompletion<ClassicBProject>() {
    override fun candidates(
        project: ClassicBProject,
        at: SourcePosition,
    ): Sequence<LazyCandidate> {
        val table = DocumentationTables.classicB
        return ClassicBKeywords.entries.asSequence().map { (word, documentation) ->
            LazyCandidate(word, CandidateKind.KEYWORD) { documentation.card(CodeLanguage.CLASSIC_B).render() }
        } +
            table.keys.asSequence().map { word ->
                LazyCandidate(word, builtin(word)) { table[word]?.let(ClassicBDocumentation::entries) }
            } +
            identifiers[project.mainFile].all.asSequence().map { card ->
                LazyCandidate(card.name, card.kind.completion) { card.documentation }
            }
    }
}
