package b.language.server.analysis.classicB

import b.language.server.analysis.LastCheck
import b.language.server.analysis.LineHover
import b.language.server.analysis.Markdown
import b.language.server.analysis.documentation.CodeLanguage
import b.language.server.analysis.documentation.DocumentationTables
import b.language.server.analysis.documentation.LineText
import b.language.server.project.SourcePosition
import b.language.server.project.classicB.ClassicBProject
import de.prob.unicode.UnicodeTranslator

/** Hovers of classical B: the machine's identifiers as ProB saw them in the last check, keywords and operators. */
class ClassicBHover(
    private val identifiers: LastCheck<IdentifierCatalog>,
) : LineHover<ClassicBProject>() {
    override fun hover(
        project: ClassicBProject,
        line: LineText,
        at: SourcePosition,
    ): Markdown? {
        val table = DocumentationTables.classicB
        val word = line.wordAt(at.column)
        if (word != null && !word.startsWith("@")) {
            identifiers[project.mainFile][word]?.let { return it.documentation }
            table[word]?.let { return ClassicBDocumentation.entries(it) }
            ClassicBKeywords[word]?.let { return it.card(CodeLanguage.CLASSIC_B).render() }
        }
        line.operatorAt(at.column, table)?.let { return ClassicBDocumentation.entries(it) }
        // ProB also reads the Unicode symbols of Atelier B.
        val char = line.charAt(at.column) ?: return null
        return table[UnicodeTranslator.toAscii(char.toString()).trim()]?.let { ClassicBDocumentation.entries(it) }
    }
}
