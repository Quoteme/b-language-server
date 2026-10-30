package b.language.server.analysis.documentation

import b.language.server.analysis.Markdown

/**
 * Documentation laid out like the hovers of other language servers: the signature in a code
 * block, then the text, examples, and links to the official sources.
 */
data class DocumentationCard(
    val language: CodeLanguage,
    val signature: String?,
    val body: List<String>,
    val examples: List<String> = emptyList(),
    val links: List<Link> = emptyList(),
) {
    fun render(): Markdown {
        val parts = mutableListOf<String>()
        signature?.let { parts += "```${language.fence}\n$it\n```" }
        val text = body.filter { it.isNotBlank() }
        if (signature != null && (text.isNotEmpty() || examples.isNotEmpty())) parts += "---"
        parts += text
        if (examples.isNotEmpty()) {
            parts += (if (examples.size == 1) "**Example**" else "**Examples**") +
                "\n```${language.fence}\n${examples.joinToString("\n")}\n```"
        }
        if (links.isNotEmpty()) parts += links.distinct().joinToString(" · ") { it.toMarkdown() }
        return Markdown(parts.joinToString("\n\n"))
    }
}

/** A keyword whose official documentation is prose, summarised by hand. */
data class KeywordDocumentation(
    val signature: String,
    val text: String,
    val example: String?,
    val link: Link,
) {
    fun card(language: CodeLanguage) = DocumentationCard(language, signature, listOf(text), listOfNotNull(example), listOf(link))
}
