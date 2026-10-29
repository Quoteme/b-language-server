package b.language.server.analysis.eventB

import b.language.server.analysis.CandidateKind
import b.language.server.analysis.LastCheck
import b.language.server.analysis.LazyCandidate
import b.language.server.analysis.ProofObligation
import b.language.server.analysis.WordCompletion
import b.language.server.analysis.documentation.CodeLanguage
import b.language.server.analysis.documentation.DocumentationTables
import b.language.server.project.SourcePosition
import b.language.server.project.eventB.EventBProject

/** Completes Event-B keywords, operators, and the identifiers of the checked model. */
class EventBCompletion(
    private val obligations: LastCheck<List<ProofObligation>>,
) : WordCompletion<EventBProject>() {
    override fun candidates(
        project: EventBProject,
        at: SourcePosition,
    ): Sequence<LazyCandidate> {
        val main = StaticChecker(project).check().main
        val table = DocumentationTables.eventB
        val keywords =
            EventBKeywords.entries.asSequence().map { (word, documentation) ->
                LazyCandidate(word, CandidateKind.KEYWORD) { documentation.card(CodeLanguage.EVENT_B).render() }
            }
        val operators =
            table.keys.asSequence().map { word ->
                LazyCandidate(word, builtin(word)) { table[word]?.firstOrNull()?.let(EventBDocumentation::operator) }
            }
        val identifiers =
            main
                ?.let { checked ->
                    names(checked, at.line).map { (name, kind) ->
                        LazyCandidate(name, kind) { EventBDocumentation.identifier(checked, name, at.line, obligations[project.mainFile]) }
                    }
                }.orEmpty()
        return keywords + operators + identifiers
    }

    private data class Name(
        val name: String,
        val kind: CandidateKind,
    )

    /** The identifiers of a checked component, and the parameters of the event at [line]. */
    private fun names(
        main: CheckedComponent,
        line: Int,
    ): Sequence<Name> =
        main.contexts.asSequence().flatMap { context ->
            context.sets.map { Name(it, CandidateKind.CLASS) } + context.constants.map { Name(it, CandidateKind.CONSTANT) }
        } +
            main.machines.asSequence().flatMap { machine ->
                machine.variables.map { Name(it, CandidateKind.VARIABLE) } + machine.events.keys.map { Name(it, CandidateKind.EVENT) }
            } +
            (main as? CheckedMachine)
                ?.eventAt(line)
                ?.parameters
                ?.keys
                .orEmpty()
                .map { Name(it, CandidateKind.VARIABLE) }
}
