package b.language.server.analysis.eventB

import b.language.server.analysis.Markdown
import b.language.server.analysis.ProofObligation
import b.language.server.analysis.Verdict
import b.language.server.analysis.documentation.CodeLanguage
import b.language.server.analysis.documentation.DocumentationCard
import b.language.server.analysis.documentation.DocumentationTables
import b.language.server.analysis.documentation.Link
import b.language.server.analysis.documentation.OperatorEntry
import de.be4.eventb.core.parser.node.TFormula
import org.eventb.core.ast.BoundIdentDecl
import org.eventb.core.ast.BoundIdentifier
import org.eventb.core.ast.FreeIdentifier
import org.eventb.core.ast.SourceLocation
import org.eventb.core.ast.Type

/**
 * Documentation of Event-B: operators from the Rodin Handbook, and identifiers and labels of a
 * checked model with their types, the formulas that constrain them, and their proof obligations.
 */
object EventBDocumentation {
    private val CARRIER_SETS = Link.handbook("context.html#carrier_sets", "3.2.3.1 Carrier Sets")
    private val CONSTANTS = Link.handbook("context.html#constants_and_axioms", "3.2.3.3 Constants and axioms")
    private val VARIABLES = Link.handbook("machine.html#variables_and_invariants", "3.2.4.3 Variables and invariants")
    private val PARAMETERS = Link.handbook("machine.html#parameters", "3.2.4.4.1 Parameters")
    private val WITNESSES = Link.handbook("machine.html#witness", "3.2.4.4.4 Witnesses")
    private val QUANTIFIED = Link.handbook("predicates.html#quantified_predicates", "3.3.2.3 Quantified predicates")
    private val NONDETERMINISTIC =
        Link.handbook("assignments.html#nondeterministic_assignments", "3.3.8.2 Non-deterministic assignment with before-after-predicate")

    private fun card(
        signature: String?,
        body: List<String>,
        links: List<Link>,
    ) = DocumentationCard(CodeLanguage.EVENT_B, signature, body, emptyList(), links).render()

    private fun typed(
        name: String,
        type: Type?,
    ) = if (type == null) name else "$name ∈ ${type.toExpression()}"

    /** The formulas among [candidates] that mention [name], as they constrain or use it. */
    private fun mentions(
        name: String,
        candidates: List<LabeledFormula<*>>,
    ): List<String> = candidates.filter { l -> l.formula.freeIdentifiers.any { it.name == name } }.take(4).map { it.show() }

    private fun mentioning(
        title: String,
        lines: List<String>,
    ) = if (lines.isEmpty()) emptyList() else listOf("$title\n```eventb\n${lines.joinToString("\n")}\n```")

    /** A named part of an operator's documentation, such as its definition. */
    private class Fact(
        val name: String,
        val lines: List<String>,
    )

    /** An operator, keeping the parts of its definition that concern its own symbol. */
    fun operator(entry: OperatorEntry): Markdown {
        val extra = DocumentationTables.extras(CodeLanguage.EVENT_B, entry.title)
        val symbol = entry.symbol

        fun relevant(
            lines: List<String>,
            definition: Boolean,
        ): List<String> {
            if (lines.isEmpty() || symbol.isEmpty()) return lines.take(3)
            if (definition) {
                // The definition of this very operator, not of its siblings in the same table.
                val own = lines.filter { line -> line.substringBefore("≙").contains(symbol) }
                return own.ifEmpty { lines.filter { symbol in it } }.ifEmpty { lines }.take(3)
            }
            // "for each operator □ of ∪, ∩, ∖: L(S □ T) ≙ …" becomes "L(S ∪ T) ≙ …".
            return lines
                .map { it.replace(Regex("""(?i)\s*for each operator □ of [^:]*:?\s*"""), " ").trim() }
                .filter { it.isNotEmpty() && (symbol in it || "□" in it) }
                .map { it.replace("□", symbol) }
                .ifEmpty { lines }
                .take(3)
        }
        val ascii = entry.ascii?.let { "Typed as $it." }
        val body = mutableListOf("**${entry.title.replaceFirstChar { it.uppercase() }}**" + (ascii?.let { " · $it" } ?: ""))
        if (entry.description.isNotEmpty()) body += entry.description.replace(" — ", "\n\n")
        val facts =
            listOf(
                Fact("Definition", relevant(entry.definition, definition = true)),
                Fact("Type", relevant(entry.types, definition = false)),
                Fact("Well-definedness", relevant(entry.wd, definition = false)),
            ).filter { it.lines.isNotEmpty() }.map { "*${it.name}:* " + it.lines.joinToString("; ") }
        if (facts.isNotEmpty()) body += facts.joinToString("  \n")
        return DocumentationCard(
            CodeLanguage.EVENT_B,
            extra?.signature ?: symbol.ifEmpty { null },
            body,
            extra?.examples.orEmpty(),
            listOf(entry.sourceLink),
        ).render()
    }

    /** The proof obligations and what ProB made of them, as Rodin lists them per element. */
    fun statuses(obligations: List<ProofObligation>): String {
        if (obligations.isEmpty()) return ""
        val lines =
            obligations.joinToString("\n") { po ->
                when (val verdict = po.verdict) {
                    Verdict.Discharged -> "- ✓ `${po.name}`"
                    is Verdict.Disproved -> {
                        "- ✗ `${po.name}` does not hold" +
                            (if (verdict.counterexample.isEmpty()) "" else ", e.g. for `${verdict.counterexample}`")
                    }
                    is Verdict.Undischarged -> "- ? `${po.name}` not discharged by ProB"
                }
            }
        return "**Proof obligations** ([3.2.7](${RodinHandbook.POS.url}))\n$lines"
    }

    /** What [word] names in the model: a set, constant, variable, component, event or parameter. */
    fun identifier(
        main: CheckedComponent,
        word: String,
        line: Int,
        obligations: List<ProofObligation>,
    ): Markdown? {
        val name = word.removeSuffix("'")
        val primed = word.endsWith("'")
        val contexts = main.contexts
        val machines = main.machines
        val axioms = contexts.flatMap { it.axioms }
        val invariants = machines.flatMap { it.invariants }

        for (context in contexts.reversed()) {
            if (name in context.sets) {
                return card(
                    "(carrier set) $name",
                    listOf("A carrier set of context `${context.name}`: a new, non-empty type.") +
                        mentioning("Axioms about it:", mentions(name, axioms)),
                    listOf(CARRIER_SETS),
                )
            }
            if (name in context.constants) {
                val type = if (context.env.contains(name)) context.env.getType(name) else null
                return card(
                    "(constant) ${typed(name, type)}",
                    listOf("A constant of context `${context.name}`.") + mentioning("Axioms about it:", mentions(name, axioms)),
                    listOf(CONSTANTS),
                )
            }
        }

        for (machine in machines) {
            if (name in machine.variables) {
                val own = main as CheckedMachine
                val type = if (own.env.contains(name)) own.env.getType(name) else null
                val kept = if (machine !== own) " of the abstract machine" else ""
                val signature = if (primed) "(after-value) ${typed(word, type)}" else "(variable$kept) ${typed(name, type)}"
                val changing = own.events.values.filter { name in it.assigned }.map { it.name }
                val here = if (primed) own.formulaAt(line)?.let { listOf("```eventb\n${it.show()}\n```") }.orEmpty() else emptyList()
                val text =
                    listOf(if (primed) "The value of `$name` after the event." else "A variable of machine `${machine.name}`.") +
                        here + mentioning("Invariants about it:", mentions(name, invariants)) +
                        (if (changing.isEmpty()) emptyList() else listOf("Changed by: " + changing.joinToString(", ") { "`$it`" }))
                return card(signature, text, listOf(VARIABLES))
            }
        }

        // Components named in sees, refines and extends.
        contexts.firstOrNull { it.name == name }?.let { context ->
            val signature =
                "context ${context.name}" +
                    (if (context.extended.isEmpty()) "" else " extends " + context.extended.joinToString(" ") { it.name }) +
                    (if (context.sets.isEmpty()) "" else "\n  sets " + context.sets.joinToString(" ")) +
                    (if (context.constants.isEmpty()) "" else "\n  constants " + context.constants.joinToString(" ")) +
                    (if (context.axioms.isEmpty()) "" else "\n  axioms\n    " + context.axioms.joinToString("\n    ") { it.show() }) +
                    "\nend"
            return card(signature, listOf("In `${context.file.name}`."), listOf(RodinHandbook.CONTEXT))
        }
        machines.firstOrNull { it.name == name }?.let { machine ->
            val signature =
                "machine ${machine.name}" + (machine.abstract?.let { " refines ${it.name}" } ?: "") +
                    (if (machine.variables.isEmpty()) "" else "\n  variables " + machine.variables.joinToString(" ")) +
                    (if (machine.invariants.isEmpty()) "" else "\n  invariants\n    " + machine.invariants.joinToString("\n    ") { it.show() }) +
                    "\n  events " + machine.events.keys.joinToString(" ") + "\nend"
            return card(signature, listOf("In `${machine.file.name}`."), listOf(RodinHandbook.MACHINE))
        }

        // Events of the machine and of its abstractions.
        for (machine in machines) {
            val event = machine.events[name] ?: continue
            val own = machine === main
            val text = mutableListOf<String>()
            if (!own) text += "An event of the abstract machine `${machine.name}`."
            if (event.parameters.isNotEmpty()) {
                text += "Parameters: " + event.parameters.entries.joinToString(", ") { (p, type) -> "`$p ∈ ${type.toExpression()}`" }
            }
            if (own) text += statuses(obligations.filter { it.name.startsWith("$name/") })
            return card(event.outline(), text, listOf(RodinHandbook.EVENTS))
        }

        // A parameter of the event the line belongs to, or of an abstract event it refines.
        val machine = main as? CheckedMachine ?: return null
        val event = machine.eventAt(line) ?: return null
        val type = event.parameters[name] ?: event.refines.firstNotNullOfOrNull { it.parameters[name] } ?: return null
        val abstract = name !in event.parameters
        val text =
            listOf(
                if (abstract) "A parameter of the abstract event, given a value by a witness." else "A parameter of event `${event.name}`.",
            ) + mentioning("Guards about it:", mentions(name, event.guards + event.witnesses))
        return card("(parameter) ${typed(name, type)}", text, listOf(PARAMETERS))
    }

    /** The labelled formula `@name`: what it is, and the proof obligations about it. */
    fun label(
        main: CheckedComponent,
        name: String,
        obligations: List<ProofObligation>,
    ): Markdown? {
        fun describe(
            kind: String,
            labeled: LabeledFormula<*>,
            link: Link,
            related: List<ProofObligation>,
        ): Markdown {
            val article = if (kind.startsWith("guard") || kind.startsWith("witness")) "a" else "an"
            return card(
                labeled.show(),
                listOf("`@$name` is ${if (labeled.theorem) "a theorem, " else ""}$article $kind.", statuses(related)),
                listOf(link),
            )
        }

        val mine = obligations.filter { it.name.startsWith("$name/") || it.name.contains("/$name/") }
        for (machine in main.machines) {
            val own = machine === main
            machine.invariants.firstOrNull { it.label == name }?.let {
                return describe("invariant of `${machine.name}`", it, VARIABLES, if (own) mine else emptyList())
            }
            for (event in machine.events.values) {
                val related = if (own) obligations.filter { it.name.startsWith("${event.name}/$name/") } else emptyList()
                event.guards.firstOrNull { it.label == name }?.let {
                    return describe("guard of `${event.name}`", it, RodinHandbook.GUARDS, related)
                }
                event.actions.firstOrNull { it.label == name }?.let {
                    return describe("action of `${event.name}`", it, RodinHandbook.ACTIONS, related)
                }
                event.witnesses.firstOrNull { it.label == name }?.let {
                    return describe("witness of `${event.name}`", it, WITNESSES, related)
                }
            }
        }
        for (context in main.contexts) {
            context.axioms.firstOrNull { it.label == name }?.let {
                return describe("axiom of `${context.name}`", it, CONSTANTS, if (context === main) mine else emptyList())
            }
        }
        return null
    }

    /** A variable bound by a quantifier, comprehension, λ or an assignment's after-value, with its type. */
    fun bound(
        main: CheckedComponent,
        word: String,
        line: Int,
        column: Int,
    ): Markdown? {
        for ((file, labeled) in main.formulas) {
            val source = labeled.source ?: continue
            if (file != main.file) continue
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
                    "(after-value) ${typed(word, type)}",
                    listOf("The value of `${word.removeSuffix("'")}` after the event, in `@${labeled.label}`.", "```eventb\n${labeled.show()}\n```"),
                    listOf(NONDETERMINISTIC),
                )
            }
            return card(
                "(bound variable) ${typed(word, type)}",
                listOf("Bound in `@${labeled.label}`:", "```eventb\n${labeled.show()}\n```"),
                listOf(QUANTIFIED),
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
}
