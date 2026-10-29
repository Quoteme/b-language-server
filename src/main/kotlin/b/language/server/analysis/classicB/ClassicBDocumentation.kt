package b.language.server.analysis.classicB

import b.language.server.analysis.Markdown
import b.language.server.analysis.documentation.CodeLanguage
import b.language.server.analysis.documentation.DocumentationCard
import b.language.server.analysis.documentation.DocumentationTables
import b.language.server.analysis.documentation.OperatorEntry

/** Documentation from ProB's Summary of B Syntax. */
object ClassicBDocumentation {
    /** The entries of a key; for a word like THEN, the constructs it belongs to. */
    fun entries(entries: List<OperatorEntry>): Markdown {
        val named = entries.filter { it.primary }.distinctBy { it.title + it.link }
        if (named.isNotEmpty()) {
            return Markdown(
                named.take(3).joinToString("\n\n---\n\n") { entry ->
                    DocumentationCard(
                        CodeLanguage.CLASSIC_B,
                        entry.title,
                        listOf(entry.description.replaceFirstChar { it.uppercase() }),
                        DocumentationTables.extras(CodeLanguage.CLASSIC_B, entry.title)?.examples.orEmpty(),
                        listOf(entry.sourceLink),
                    ).render().text
                },
            )
        }
        val parts = entries.distinctBy { it.title }.take(8)
        val example = parts.firstNotNullOfOrNull { DocumentationTables.extras(CodeLanguage.CLASSIC_B, it.title)?.examples?.firstOrNull() }
        return DocumentationCard(
            CodeLanguage.CLASSIC_B,
            parts.joinToString("\n") { it.title },
            listOf("Part of the constructs above."),
            listOfNotNull(example),
            parts.map { it.sourceLink },
        ).render()
    }
}
