package b.language.server.analysis.eventB

import b.language.server.analysis.LastCheck
import b.language.server.analysis.LineHover
import b.language.server.analysis.Markdown
import b.language.server.analysis.ProofObligation
import b.language.server.analysis.documentation.CodeLanguage
import b.language.server.analysis.documentation.DocumentationTables
import b.language.server.analysis.documentation.LineText
import b.language.server.project.SourcePosition
import b.language.server.project.eventB.EventBProject

/** Hovers of Event-B: operators, keywords, identifiers and labels, with their types and proof obligations. */
class EventBHover(
    private val obligations: LastCheck<List<ProofObligation>>,
) : LineHover<EventBProject>() {
    override fun hover(
        project: EventBProject,
        line: LineText,
        at: SourcePosition,
    ): Markdown? {
        val table = DocumentationTables.eventB
        val word = line.wordAt(at.column) ?: return line.operatorAt(at.column, table)?.let { EventBDocumentation.operator(it.first()) }
        val proofObligations = obligations[project.mainFile]
        if (word.startsWith("@")) {
            val main = StaticChecker(project).check().main ?: return null
            return EventBDocumentation.label(main, word.removePrefix("@"), proofObligations)
        }
        EventBKeywords[word]?.let { return it.card(CodeLanguage.EVENT_B).render() }
        StaticChecker(project).check().main?.let { main ->
            EventBDocumentation.bound(main, word, at.line, at.column)?.let { return it }
            EventBDocumentation.identifier(main, word, at.line, proofObligations)?.let { return it }
        }
        table[word]?.let { return EventBDocumentation.operator(it.first()) }
        return line.operatorAt(at.column, table)?.let { EventBDocumentation.operator(it.first()) }
    }
}
