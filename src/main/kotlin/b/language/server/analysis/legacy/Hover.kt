package b.language.server.analysis.legacy

import de.be4.eventb.core.parser.node.TFormula
import de.prob.unicode.UnicodeTranslator
import org.eventb.core.ast.BoundIdentDecl
import org.eventb.core.ast.BoundIdentifier
import org.eventb.core.ast.FreeIdentifier
import org.eventb.core.ast.SourceLocation
import java.io.File

/** Hover documentation for whatever is under the cursor: operators, keywords, identifiers and labels. */
object Hover : Documentation() {
    // ----------------------------------------------------------------------
    // Entry point

    /**
     * The hover text for the position (0-based line and UTF-16 column) in [file], or null.
     * [text] is the file's content as the editor has it; [obligations] and [identifiers] come
     * from the file's last check.
     */
    fun at(
        file: File,
        text: String,
        line: Int,
        column: Int,
        obligations: List<ProofObligation>,
        identifiers: Map<String, String>,
    ): String? {
        val source = text.lines().getOrNull(line) ?: return null
        return if (CamilleProject.isCamille(text)) {
            eventb(file, source, line, column, obligations)
        } else {
            classical(source, column, identifiers)
        }
    }

    // ----------------------------------------------------------------------
    // Event-B

    private fun eventb(
        file: File,
        source: String,
        line: Int,
        column: Int,
        obligations: List<ProofObligation>,
    ): String? {
        val word = wordAt(source, column)
        if (word == null) return operator(source, column, eventbTable)?.let { renderOperator(it.first()) }
        if (word.startsWith("@")) return label(file, word.removePrefix("@"), obligations)
        eventbKeywords[word]?.let { return render(it, "eventb") }
        val info = checked(file)
        if (info != null) {
            bound(info, word, line, column)?.let { return it }
            identifier(info, word, line, obligations)?.let { return it }
        }
        eventbTable[word]?.let { return renderOperator(it.first()) }
        return operator(source, column, eventbTable)?.let { renderOperator(it.first()) }
    }

    /** A variable bound by a quantifier, comprehension, λ or an assignment's after-value, with its type. */
    private fun bound(
        info: Any,
        word: String,
        line: Int,
        column: Int,
    ): String? {
        val main = (info as? MachineInfo)?.component?.file ?: (info as? ContextInfo)?.component?.file ?: return null
        for ((file, labeled) in formulas(info)) {
            val source = labeled.source ?: continue
            if (file != main) continue
            val offset = offsetIn(source, line, column) ?: continue
            val sub =
                try {
                    labeled.formula.getPosition(SourceLocation(offset, offset))?.let { labeled.formula.getSubFormula(it) }
                } catch (e: RuntimeException) {
                    null
                } ?: return null
            val type =
                when (sub) {
                    is BoundIdentDecl -> sub.type
                    is BoundIdentifier -> sub.type
                    is FreeIdentifier -> if (sub.isPrimed) sub.type else return null
                    else -> return null
                }
            if (word.endsWith("'")) {
                return card(
                    "eventb",
                    "(after-value) ${typed(word, type)}",
                    listOf(
                        "The value of `${word.removeSuffix("'")}` after the event, in `@${labeled.label}`.",
                        "```eventb\n${show(labeled)}\n```",
                    ),
                    emptyList(),
                    listOf(
                        handbook(
                            "assignments.html#nondeterministic_assignments",
                            "3.3.8.2 Non-deterministic assignment with before-after-predicate",
                        ),
                    ),
                )
            }
            return card(
                "eventb",
                "(bound variable) ${typed(word, type)}",
                listOf("Bound in `@${labeled.label}`:", "```eventb\n${show(labeled)}\n```"),
                emptyList(),
                listOf(handbook("predicates.html#quantified_predicates", "3.3.2.3 Quantified predicates")),
            )
        }
        return null
    }

    /** The offset of (line, column) in the text of [formula], if it lies inside. */
    private fun offsetIn(
        formula: TFormula,
        line: Int,
        column: Int,
    ): Int? {
        var l = formula.line - 1
        var c = formula.pos - 1
        for ((index, char) in formula.text.withIndex()) {
            if (l == line && c == column) return index
            if (char == '\n') {
                l++
                c = 0
            } else {
                c++
            }
        }
        return null
    }

    private fun label(
        file: File,
        name: String,
        obligations: List<ProofObligation>,
    ): String? {
        val info = checked(file) ?: return null

        fun describe(
            kind: String,
            labeled: Labeled<*>,
            where: Pair<String, String>,
            related: List<ProofObligation>,
        ) = card(
            "eventb",
            show(labeled),
            listOf(
                "`@$name` is ${if (labeled.theorem) "a theorem, " else ""}an $kind."
                    .replace(
                        "an guard",
                        "a guard",
                    ).replace("an witness", "a witness"),
                statuses(related),
            ),
            emptyList(),
            listOf(where),
        )

        val mine = obligations.filter { it.name.startsWith("$name/") || it.name.contains("/$name/") }
        for (machine in machinesOf(info)) {
            val own = machine === info
            machine.invariants.firstOrNull { it.label == name }?.let {
                return describe(
                    "invariant of `${machine.name}`",
                    it,
                    handbook("machine.html#variables_and_invariants", "3.2.4.3 Variables and invariants"),
                    if (own) mine else emptyList(),
                )
            }
            for (event in machine.events.values) {
                val related = if (own) obligations.filter { it.name.startsWith("${event.name}/$name/") } else emptyList()
                event.guards.firstOrNull { it.label == name }?.let { return describe("guard of `${event.name}`", it, GUARDS, related) }
                event.actions.firstOrNull { it.label == name }?.let { return describe("action of `${event.name}`", it, ACTIONS, related) }
                event.witnesses
                    .firstOrNull {
                        it.label == name
                    }?.let {
                        return describe(
                            "witness of `${event.name}`",
                            it,
                            handbook("machine.html#witness", "3.2.4.4.4 Witnesses"),
                            related,
                        )
                    }
            }
        }
        for (context in contextsOf(info)) {
            context.axioms.firstOrNull { it.label == name }?.let {
                return describe(
                    "axiom of `${context.name}`",
                    it,
                    handbook("context.html#constants_and_axioms", "3.2.3.3 Constants and axioms"),
                    if (context ===
                        info
                    ) {
                        mine
                    } else {
                        emptyList()
                    },
                )
            }
        }
        return null
    }

    // ----------------------------------------------------------------------
    // Classical B

    private fun classical(
        source: String,
        column: Int,
        identifiers: Map<String, String>,
    ): String? {
        val word = wordAt(source, column)
        if (word != null && !word.startsWith("@")) {
            identifiers[word]?.let { return it }
            classicalTable[word]?.let { return renderClassical(it) }
            classicalKeywords[word]?.let { return render(it, "b") }
        }
        operator(source, column, classicalTable)?.let { return renderClassical(it) }
        // ProB also reads the Unicode symbols of Atelier B.
        val char = source.getOrNull(column) ?: return null
        val ascii = UnicodeTranslator.toAscii(char.toString()).trim()
        return classicalTable[ascii]?.let { renderClassical(it) }
    }

    // ----------------------------------------------------------------------
    // Finding what is under the cursor

    private fun isSymbolLetter(c: Char) = c in "ℕℤℙλ"

    private fun wordAt(
        source: String,
        column: Int,
    ): String? {
        if (column !in source.indices) return null
        val char = source[column]
        if (!(isWordChar(char) || char == '@') || isSymbolLetter(char)) return null
        var start = column
        while (start > 0 && (isWordChar(source[start - 1]) || source[start - 1] == '@') && !isSymbolLetter(source[start - 1])) start--
        var end = column
        while (end < source.length && isWordChar(source[end]) && !isSymbolLetter(source[end])) end++
        return source.substring(start, end).takeIf { it.isNotBlank() && it != "'" }
    }

    /** The longest operator of [table] that covers the column. */
    private fun operator(
        source: String,
        column: Int,
        table: Map<String, List<Entry>>,
    ): List<Entry>? {
        if (column !in source.indices) return null
        for (length in 5 downTo 1) {
            for (start in (column - length + 1)..column) {
                if (start < 0 || start + length > source.length) continue
                val candidate = source.substring(start, start + length)
                if (candidate.isBlank() || (length > 1 && candidate.all { it.isLetterOrDigit() })) continue
                table[candidate]?.let { return it }
            }
        }
        return null
    }
}
