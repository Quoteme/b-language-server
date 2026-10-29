package b.language.server.proBMangement.prob

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.io.File

/** Completion of keywords, built-ins and identifiers, documented by the same cards as [Hover]. */
object Completion : Documentation() {
    /** A completion candidate, documented by its hover card, which is rendered only if it matches. */
    private class Candidate(
        val word: String,
        val kind: CompletionItemKind,
        val card: () -> String?,
    )

    /** Keywords, built-ins and identifiers starting with the word left of the cursor, documented by their hover cards. */
    fun at(
        file: File,
        text: String,
        line: Int,
        column: Int,
        obligations: List<ProofObligation>,
        identifiers: Map<String, String>,
    ): List<CompletionItem> {
        val prefix =
            text
                .lines()
                .getOrElse(line) { "" }
                .take(column)
                .takeLastWhile(::isWordChar)
        if (prefix.isEmpty()) return emptyList()
        val candidates =
            if (CamilleProject.isCamille(text)) {
                val info = checked(file)
                eventbKeywords.asSequence().map { (word, doc) -> Candidate(word, CompletionItemKind.Keyword) { render(doc, "eventb") } } +
                    eventbTable.asSequence().map { (word, entries) -> Candidate(word, builtin(word)) { renderOperator(entries.first()) } } +
                    names(info, line).map { (name, kind) ->
                        Candidate(name, kind) { info?.let { identifier(it, name, line, obligations) } }
                    }
            } else {
                classicalKeywords.asSequence().map { (word, doc) -> Candidate(word, CompletionItemKind.Keyword) { render(doc, "b") } } +
                    classicalTable.asSequence().map { (word, entries) -> Candidate(word, builtin(word)) { renderClassical(entries) } } +
                    identifiers.asSequence().map { (name, card) -> Candidate(name, kindOf(card)) { card } }
            }
        return candidates
            .filter { it.word.all(::isWordChar) && it.word.startsWith(prefix, ignoreCase = true) }
            .distinctBy { it.word }
            .map { candidate ->
                CompletionItem(candidate.word).apply {
                    kind = candidate.kind
                    candidate.card()?.let { card ->
                        detail = card.takeIf { it.startsWith("```") }?.lines()?.getOrNull(1)
                        documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, card))
                    }
                }
            }.toList()
    }

    /** Reserved words of B are upper case, such as INTEGER or INVARIANT; the others are operators such as card. */
    private fun builtin(word: String) = if (word.any(Char::isLowerCase)) CompletionItemKind.Function else CompletionItemKind.Keyword

    /** The identifiers of a checked Event-B component, and the parameters of the event at [line]. */
    private fun names(
        info: Any?,
        line: Int,
    ): Sequence<Pair<String, CompletionItemKind>> =
        info?.let(::contextsOf).orEmpty().asSequence().flatMap { context ->
            context.sets.map { it to CompletionItemKind.Class } + context.constants.map { it to CompletionItemKind.Constant }
        } +
            info?.let(::machinesOf).orEmpty().asSequence().flatMap { machine ->
                machine.variables.map { it to CompletionItemKind.Variable } + machine.events.keys.map { it to CompletionItemKind.Event }
            } +
            (info as? MachineInfo)
                ?.let { eventAt(it, line) }
                ?.parameters
                ?.keys
                .orEmpty()
                .map { it to CompletionItemKind.Variable }

    /** The kind of a classical B identifier, from the tag of its hover card such as `(variable)`. */
    private fun kindOf(card: String) =
        when (
            card
                .lines()
                .getOrNull(1)
                ?.substringAfter('(')
                ?.substringBefore(')')
        ) {
            "variable" -> CompletionItemKind.Variable
            "constant" -> CompletionItemKind.Constant
            "operation" -> CompletionItemKind.Function
            "element" -> CompletionItemKind.EnumMember
            "enumerated set" -> CompletionItemKind.Enum
            "definition" -> CompletionItemKind.Value
            else -> CompletionItemKind.Class
        }
}
