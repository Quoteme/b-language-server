package b.language.server.analysis.eventB

import b.language.server.analysis.prob.Transitions
import b.language.server.project.eventB.EventBProject.Component
import de.be4.eventb.core.parser.node.TFormula
import de.be4.eventb.core.parser.node.Token
import org.eventb.core.ast.Assignment
import org.eventb.core.ast.Expression
import org.eventb.core.ast.Formula
import org.eventb.core.ast.ITypeEnvironmentBuilder
import org.eventb.core.ast.Predicate
import org.eventb.core.ast.Type
import java.io.File

/**
 * A labelled, type-checked formula and where it is written. [source] is the formula's text in the
 * file, unless it was written in ASCII and translated, which moves its source locations.
 */
class LabeledFormula<T : Formula<T>>(
    val label: String,
    val token: Token,
    val formula: T,
    val theorem: Boolean = false,
    val source: TFormula? = null,
) {
    /** How the formula reads in the source. */
    fun show(): String =
        (if (theorem) "theorem " else "") + (if (label == VARIANT) "variant " else "@$label ") +
            (source?.text?.trim()?.replace(Regex("""\s+"""), " ") ?: formula.toString())

    companion object {
        /** The label of a machine's variant, which has none in the source. */
        const val VARIANT = "variant"
    }
}

/** A labelled formula and the file it is written in. */
data class FormulaInFile(
    val file: File,
    val formula: LabeledFormula<*>,
)

/** A machine or context that passed the static checks, with the types of its identifiers. */
sealed interface CheckedComponent {
    val name: String
    val component: Component
    val env: ITypeEnvironmentBuilder

    val file: File get() = component.file

    /** The contexts in scope: those a machine sees, or a context and those it extends. */
    val contexts: List<CheckedContext>

    /** A machine and the machines it refines, the most concrete first. */
    val machines: List<CheckedMachine>

    /** Every labelled formula of the model, with the file it is written in. */
    val formulas: List<FormulaInFile>
        get() =
            contexts.flatMap { c -> c.axioms.map { FormulaInFile(c.file, it) } } +
                machines.flatMap { m ->
                    (m.invariants + listOfNotNull(m.variant) + m.events.values.flatMap { it.guards + it.actions + it.witnesses })
                        .map { FormulaInFile(m.file, it) }
                }

    /** The labelled formula of this component written on [line]. */
    fun formulaAt(line: Int): LabeledFormula<*>? =
        formulas
            .firstOrNull { (file, labeled) ->
                val source = labeled.source
                file == this.file && source != null && line in (source.line - 1)..(source.line - 1 + source.text.count { it == '\n' })
            }?.formula
}

class CheckedContext(
    override val name: String,
    override val component: Component,
    override val env: ITypeEnvironmentBuilder,
    /** Every visible set and constant, mapped to the name of the context declaring it. */
    val origins: Map<String, String>,
    val sets: List<String>,
    val constants: List<String>,
    val extended: List<CheckedContext>,
    val axioms: List<LabeledFormula<Predicate>>,
) : CheckedComponent {
    /** This context and everything it extends, dependencies first. */
    fun closure(): LinkedHashSet<CheckedContext> {
        val result = LinkedHashSet<CheckedContext>()
        extended.forEach { result += it.closure() }
        result += this
        return result
    }

    override val contexts: List<CheckedContext> get() = closure().toList()
    override val machines: List<CheckedMachine> get() = emptyList()
}

enum class Convergence { ORDINARY, CONVERGENT, ANTICIPATED }

class CheckedEvent(
    val name: String,
    val token: Token,
    val convergence: Convergence,
    val refines: List<CheckedEvent>,
    val extended: Boolean,
    val parameters: Map<String, Type>,
    /** Guards and actions include the inherited ones of an extended event. */
    val guards: List<LabeledFormula<Predicate>>,
    val actions: List<LabeledFormula<Assignment>>,
    val witnesses: List<LabeledFormula<Predicate>>,
) {
    val isInitialisation get() = name == Transitions.INITIALISATION
    val assigned: Set<String> get() = actions.flatMap { a -> a.formula.assignedIdentifiers.map { it.name } }.toSet()

    /** How the event reads in the source, compactly. */
    fun outline(): String {
        val lines = mutableListOf<String>()
        val prefix = if (convergence == Convergence.ORDINARY) "" else convergence.name.lowercase() + " "
        val refinement =
            when {
                refines.isEmpty() -> ""
                extended -> " extends " + refines.first().name
                else -> " refines " + refines.joinToString(" ") { it.name }
            }
        lines += "${prefix}event $name$refinement"
        if (parameters.isNotEmpty()) lines += "  any " + parameters.keys.joinToString(" ")
        if (guards.isNotEmpty()) lines += "  where " + guards.joinToString("\n        ") { it.show() }
        if (witnesses.isNotEmpty()) lines += "  with " + witnesses.joinToString("\n       ") { it.show() }
        if (actions.isNotEmpty()) lines += "  then " + actions.joinToString("\n       ") { it.show() }
        lines += "end"
        return lines.joinToString("\n")
    }
}

class CheckedMachine(
    override val name: String,
    override val component: Component,
    override val env: ITypeEnvironmentBuilder,
    override val contexts: List<CheckedContext>,
    val abstract: CheckedMachine?,
    val variables: List<String>,
    val invariants: List<LabeledFormula<Predicate>>,
    val variant: LabeledFormula<Expression>?,
    val events: Map<String, CheckedEvent>,
) : CheckedComponent {
    val allVariables: Set<String> get() = variables.toSet() + abstract?.allVariables.orEmpty()
    val abstractInvariants: List<Predicate> get() = abstract?.let { it.abstractInvariants + it.invariants.map { i -> i.formula } }.orEmpty()

    override val machines: List<CheckedMachine> get() = generateSequence(this) { it.abstract }.toList()

    /** The event whose declaration starts last before [line]. */
    fun eventAt(line: Int): CheckedEvent? = events.values.filter { it.token.line - 1 <= line }.maxByOrNull { it.token.line }
}
