package b.language.server.proBMangement.prob

import de.be4.eventb.core.parser.BException
import de.be4.eventb.core.parser.EventBLexerException
import de.be4.eventb.core.parser.EventBParseException
import de.be4.eventb.core.parser.EventBParser
import de.be4.eventb.core.parser.lexer.LexerException
import de.be4.eventb.core.parser.node.AAction
import de.be4.eventb.core.parser.node.AAnticipatedConvergence
import de.be4.eventb.core.parser.node.AAxiom
import de.be4.eventb.core.parser.node.ACarrierSet
import de.be4.eventb.core.parser.node.AConstant
import de.be4.eventb.core.parser.node.AContextParseUnit
import de.be4.eventb.core.parser.node.AConvergentConvergence
import de.be4.eventb.core.parser.node.ADerivedAxiom
import de.be4.eventb.core.parser.node.ADerivedGuard
import de.be4.eventb.core.parser.node.ADerivedInvariant
import de.be4.eventb.core.parser.node.AEvent
import de.be4.eventb.core.parser.node.AExtendedEventRefinement
import de.be4.eventb.core.parser.node.AGuard
import de.be4.eventb.core.parser.node.AInvariant
import de.be4.eventb.core.parser.node.AMachineParseUnit
import de.be4.eventb.core.parser.node.AParameter
import de.be4.eventb.core.parser.node.ARefinesEventRefinement
import de.be4.eventb.core.parser.node.AVariable
import de.be4.eventb.core.parser.node.AVariant
import de.be4.eventb.core.parser.node.AWitness
import de.be4.eventb.core.parser.node.PParseUnit
import de.be4.eventb.core.parser.node.TFormula
import de.be4.eventb.core.parser.node.TIdentifierLiteral
import de.be4.eventb.core.parser.node.TLabel
import de.be4.eventb.core.parser.node.Token
import de.prob.animator.domainobjects.EventB
import de.prob.model.eventb.Context
import de.prob.model.eventb.Event
import de.prob.model.eventb.EventBAction
import de.prob.model.eventb.EventBAxiom
import de.prob.model.eventb.EventBConstant
import de.prob.model.eventb.EventBGuard
import de.prob.model.eventb.EventBInvariant
import de.prob.model.eventb.EventBMachine
import de.prob.model.eventb.EventBModel
import de.prob.model.eventb.EventBVariable
import de.prob.model.eventb.EventParameter
import de.prob.model.eventb.Variant
import de.prob.model.eventb.Witness
import de.prob.model.representation.AbstractElement
import de.prob.model.representation.DependencyGraph.ERefType
import de.prob.model.representation.ModelElementList
import de.prob.unicode.UnicodeTranslator
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eventb.core.ast.ASTProblem
import org.eventb.core.ast.Assignment
import org.eventb.core.ast.Expression
import org.eventb.core.ast.Formula
import org.eventb.core.ast.FormulaFactory
import org.eventb.core.ast.IParseResult
import org.eventb.core.ast.ITypeEnvironmentBuilder
import org.eventb.core.ast.IntegerType
import org.eventb.core.ast.PowerSetType
import org.eventb.core.ast.Predicate
import org.eventb.core.ast.SourceLocation
import org.eventb.core.ast.Type
import java.io.File
import de.prob.model.representation.Set as ProBSet

/** A component file and its parse tree. */
class Component(
    val file: File,
    val unit: PParseUnit,
)

/**
 * A labelled, type-checked formula and where it is written. [source] is the formula's text in the
 * file, unless it was written in ASCII and translated, which moves its source locations.
 */
class Labeled<T : Formula<T>>(
    val label: String,
    val token: Token,
    val formula: T,
    val theorem: Boolean = false,
    val source: TFormula? = null,
)

class ContextInfo(
    val name: String,
    val component: Component,
    val env: ITypeEnvironmentBuilder,
    /** Every visible set and constant, mapped to the context declaring it. */
    val origins: Map<String, String>,
    val sets: List<String>,
    val constants: List<String>,
    val extended: List<ContextInfo>,
    val axioms: List<Labeled<Predicate>>,
) {
    /** This context and everything it extends, dependencies first. */
    fun closure(): LinkedHashSet<ContextInfo> {
        val result = LinkedHashSet<ContextInfo>()
        extended.forEach { result += it.closure() }
        result += this
        return result
    }
}

enum class Convergence { ORDINARY, CONVERGENT, ANTICIPATED }

class EventInfo(
    val name: String,
    val token: Token,
    val convergence: Convergence,
    val refines: List<EventInfo>,
    val extended: Boolean,
    val parameters: Map<String, Type>,
    /** Guards and actions include the inherited ones of an extended event. */
    val guards: List<Labeled<Predicate>>,
    val actions: List<Labeled<Assignment>>,
    val witnesses: List<Labeled<Predicate>>,
) {
    val isInitialisation get() = name == INITIALISATION
    val assigned: Set<String> get() = actions.flatMap { a -> a.formula.assignedIdentifiers.map { it.name } }.toSet()
}

class MachineInfo(
    val name: String,
    val component: Component,
    val env: ITypeEnvironmentBuilder,
    val contexts: List<ContextInfo>,
    val abstract: MachineInfo?,
    val variables: List<String>,
    val invariants: List<Labeled<Predicate>>,
    val variant: Labeled<Expression>?,
    val events: Map<String, EventInfo>,
) {
    val allVariables: Set<String> get() = variables.toSet() + abstract?.allVariables.orEmpty()
    val abstractInvariants: List<Predicate> get() = abstract?.let { it.abstractInvariants + it.invariants.map { i -> i.formula } }.orEmpty()
}

const val INITIALISATION = "INITIALISATION"

/**
 * Event-B machines and contexts written in Camille notation (`machine m0 sees c0 ...`).
 *
 * ProB only loads Event-B from Rodin projects, so the components are parsed
 * here and checked with the rules of Rodin's static checker, using Rodin's
 * AST library for the formulas.
 */
class CamilleProject(
    private val mainFile: File,
) {
    companion object {
        private val FIRST_WORD = Regex("""\A(?:\s|//[^\n]*|/\*[\s\S]*?\*/)*([A-Za-z_]+)""")
        private val CONTEXT_CLAUSES = setOf("extends", "sets", "constants", "axioms")
        private val MACHINE_CLAUSES = setOf("refines", "sees", "variables", "invariants", "variant", "events")

        /** Camille keywords are lowercase, so classical B (MACHINE ...) never matches. */
        fun isCamille(text: String) = FIRST_WORD.find(text)?.groupValues?.get(1) in setOf("machine", "context")
    }

    private enum class Kind { PREDICATE, EXPRESSION, ASSIGNMENT }

    private val factory = FormulaFactory.getDefault()
    private val components = LinkedHashMap<String, Component>()
    private val diagnostics = mutableListOf<Diagnostic>()
    private val contexts = HashMap<String, ContextInfo?>()
    private val machines = HashMap<String, MachineInfo?>()

    /** Formulas parsed only after translating ASCII to Unicode. */
    private val translated = HashSet<TFormula>()
    private var main: Component? = null

    /** The checked main component, if the static checks got that far. */
    var mainInfo: Any? = null
        private set

    /** Parses and checks the main file and every component it depends on. */
    fun check(): List<Diagnostic> {
        main = load(mainFile, null, null) ?: return diagnostics
        mainInfo =
            when (val unit = main!!.unit) {
                is AMachineParseUnit -> checkMachine(unit.name.text)
                is AContextParseUnit -> checkContext(unit.name.text)
                else -> null
            }
        return diagnostics
    }

    // ----------------------------------------------------------------------
    // Loading

    private fun load(
        file: File,
        referencedFrom: Component?,
        reference: Token?,
    ): Component? {
        if (!file.isFile) {
            if (referencedFrom != null && reference != null) {
                report(referencedFrom.file, reference, "Component '${reference.text}' not found, expected it in ${file.name}")
            }
            return null
        }
        val text = file.readText()
        val unit =
            try {
                EventBParser().parse(text).pParseUnit
            } catch (e: BException) {
                reportParseError(file, FIRST_WORD.find(text)?.groupValues?.get(1), e)
                return null
            }
        val component = Component(file, unit)
        val name =
            when (unit) {
                is AMachineParseUnit -> unit.name
                is AContextParseUnit -> unit.name
                else -> return null
            }
        components[name.text] = component
        return component
    }

    /** Finds a referenced component next to the file that references it. */
    private fun resolve(
        from: Component,
        name: TIdentifierLiteral,
    ): Component? {
        components[name.text]?.let { return it }
        val dir = from.file.absoluteFile.parentFile
        val candidates = listOf(from.file.extension, "mch").distinct().map { File(dir, "${name.text}.$it") }
        val file = candidates.firstOrNull { it.isFile } ?: candidates.first()
        return load(file, from, name)
    }

    private fun reportParseError(
        file: File,
        kind: String?,
        e: BException,
    ) {
        // SableCC prefixes messages with "[line,pos]"; the range already says where.
        fun message(raw: String?) = raw?.replace(Regex("""^\[\d+,\d+]\s*"""), "") ?: "Syntax error"
        when (val cause = e.cause) {
            is EventBParseException -> {
                val token = cause.token
                val hint =
                    when {
                        token == null -> {
                            null
                        }

                        kind == "machine" && token.text in CONTEXT_CLAUSES -> {
                            "extends, sets, constants and axioms belong in a context, not in a machine"
                        }

                        kind == "context" && token.text in MACHINE_CLAUSES -> {
                            "refines, sees, variables, invariants, variant and events belong in a machine, not in a context"
                        }

                        else -> {
                            null
                        }
                    }
                val text = hint?.let { "$it (${message(cause.message)})" } ?: message(cause.message)
                if (token != null) report(file, token, text) else report(file, 1, 1, 1, text)
            }

            is EventBLexerException -> {
                report(file, cause.lastLine, cause.lastPos, cause.lastText?.length ?: 1, message(cause.message))
            }

            is LexerException -> {
                val position = Regex("""\[(\d+),(\d+)]\s*(.*)""").find(cause.message ?: "")
                if (position != null) {
                    val (line, pos, text) = position.destructured
                    report(file, line.toInt(), pos.toInt(), 1, text)
                } else {
                    report(file, 1, 1, 1, cause.message ?: "Syntax error")
                }
            }

            else -> {
                report(file, 1, 1, 1, e.message ?: "Syntax error")
            }
        }
    }

    // ----------------------------------------------------------------------
    // Static checks, following Rodin's static checker (Rodin Handbook 3.2)

    private fun checkContext(name: String): ContextInfo? {
        if (name in contexts) return contexts[name]
        contexts[name] = null // guards against cyclic extends
        val component = components[name] ?: return null
        val unit = component.unit as? AContextParseUnit ?: return null
        val file = component.file

        val env = factory.makeTypeEnvironment()
        val origins = LinkedHashMap<String, String>()
        val extended = mutableListOf<ContextInfo>()
        for (reference in unit.extendsNames) {
            val target = resolve(component, reference) ?: continue
            if (target.unit !is AContextParseUnit) {
                report(file, reference, "'${reference.text}' is not a context")
                continue
            }
            val info = checkContext(reference.text) ?: continue
            for ((identifier, origin) in info.origins) {
                val known = origins[identifier]
                if (known != null && known != origin) {
                    report(file, reference, "'$identifier' is already declared in $known")
                } else {
                    origins[identifier] = origin
                }
            }
            env.addAll(info.env)
            extended += info
        }

        val sets = mutableListOf<String>()
        for (set in unit.sets.filterIsInstance<ACarrierSet>()) {
            if (declare(file, set.name, origins, name)) {
                env.addGivenSet(set.name.text)
                sets += set.name.text
            }
        }
        val constantTokens =
            unit.constants
                .filterIsInstance<AConstant>()
                .map { it.name }
                .filter { declare(file, it, origins, name) }

        val scope = origins.keys
        val axioms = mutableListOf<Labeled<Predicate>>()
        var typed = true
        val labels = mutableSetOf<String>()
        for (axiom in unit.axioms) {
            val (label, text, theorem) =
                when (axiom) {
                    is AAxiom -> Triple(axiom.name, axiom.predicate, false)
                    is ADerivedAxiom -> Triple(axiom.name, axiom.predicate, true)
                    else -> continue
                }
            checkLabel(file, label, labels)
            val predicate = checkFormula(file, label, text, Kind.PREDICATE, env, scope) as Predicate?
            if (predicate == null) typed = false else axioms += Labeled(labelText(label), label, predicate, theorem, located(text))
        }
        // Missing types are only worth reporting if the axioms themselves were fine.
        for (constant in constantTokens.takeIf { typed }.orEmpty()) {
            if (!env.contains(constant.text)) {
                report(file, constant, "Constant '${constant.text}' has no type; add an axiom such as ${constant.text} ∈ S")
            }
        }
        val info = ContextInfo(name, component, env, origins, sets, constantTokens.map { it.text }, extended, axioms)
        contexts[name] = info
        return info
    }

    private fun checkMachine(name: String): MachineInfo? {
        if (name in machines) return machines[name]
        machines[name] = null // guards against cyclic refinement
        val component = components[name] ?: return null
        val unit = component.unit as? AMachineParseUnit ?: return null
        val file = component.file

        val env = factory.makeTypeEnvironment()
        var abstract: MachineInfo? = null
        unit.refinesNames.firstOrNull()?.let { refined ->
            val target = resolve(component, refined) ?: return@let
            if (target.unit !is AMachineParseUnit) {
                report(file, refined, "'${refined.text}' is not a machine")
                return@let
            }
            abstract = checkMachine(refined.text)
            abstract?.let { env.addAll(it.env) }
        }

        val seen = LinkedHashSet<ContextInfo>(abstract?.contexts.orEmpty())
        for (reference in unit.seenNames) {
            val target = resolve(component, reference) ?: continue
            if (target.unit !is AContextParseUnit) {
                report(file, reference, "'${reference.text}' is not a context")
                continue
            }
            checkContext(reference.text)?.let { seen += it.closure() }
        }
        val origins = LinkedHashMap<String, String>()
        for (context in seen) {
            context.origins.forEach { (identifier, origin) -> origins.putIfAbsent(identifier, origin) }
            env.addAll(context.env)
        }
        val contextNames = origins.keys.toSet()

        // Abstract variables may be kept; once gone, they may not come back.
        val abstractVariables = abstract?.variables.orEmpty().toSet()
        val vanished = abstract?.allVariables.orEmpty() - abstractVariables
        val variables = mutableListOf<String>()
        val variableTokens = mutableListOf<TIdentifierLiteral>()
        for (variable in unit.variables.filterIsInstance<AVariable>().map { it.name }) {
            when (variable.text) {
                in vanished -> {
                    report(
                        file,
                        variable,
                        "Abstract variable '${variable.text}' disappeared in an earlier refinement and cannot be re-introduced",
                    )
                }

                in abstractVariables -> {
                    if (variable.text in variables) {
                        report(file, variable, "'${variable.text}' is already declared")
                    } else {
                        variables += variable.text
                        variableTokens += variable
                    }
                }

                else -> {
                    if (declare(file, variable, origins, name)) {
                        variables += variable.text
                        variableTokens += variable
                    }
                }
            }
        }

        val allVariables = variables.toSet() + abstract?.allVariables.orEmpty()
        val invariants = mutableListOf<Labeled<Predicate>>()
        val labels = mutableSetOf<String>()
        var typed = true
        for (invariant in unit.invariants) {
            val (label, text, theorem) =
                when (invariant) {
                    is AInvariant -> Triple(invariant.name, invariant.predicate, false)
                    is ADerivedInvariant -> Triple(invariant.name, invariant.predicate, true)
                    else -> continue
                }
            checkLabel(file, label, labels)
            val predicate = checkFormula(file, label, text, Kind.PREDICATE, env, contextNames + allVariables) as Predicate?
            if (predicate == null) typed = false else invariants += Labeled(labelText(label), label, predicate, theorem, located(text))
        }
        for (variable in variableTokens.takeIf { typed }.orEmpty()) {
            if (!env.contains(variable.text)) {
                report(file, variable, "Variable '${variable.text}' has no type; add an invariant such as ${variable.text} ∈ S")
            }
        }

        val variant =
            (unit.variant as? AVariant)?.let { node ->
                val expression =
                    checkFormula(
                        file,
                        null,
                        node.expression,
                        Kind.EXPRESSION,
                        env.makeBuilder(),
                        contextNames + variables,
                    ) as Expression?
                when {
                    expression == null -> {
                        null
                    }

                    expression.type !is IntegerType && expression.type !is PowerSetType -> {
                        report(
                            file,
                            node.expression,
                            null,
                            "Variant must be an integer or a set, but has type ${expression.type}",
                            DiagnosticSeverity.Error,
                        )
                        null
                    }

                    else -> {
                        Labeled("variant", node.expression, expression, source = located(node.expression))
                    }
                }
            }

        val scope = MachineScope(contextNames, variables, abstract)
        val events = LinkedHashMap<String, EventInfo>()
        val eventNames = mutableSetOf<String>()
        for (event in unit.events.filterIsInstance<AEvent>()) {
            if (!eventNames.add(event.name.text)) {
                report(file, event.name, "Event '${event.name.text}' is already declared")
                continue
            }
            if (event.convergence is AConvergentConvergence && unit.variant == null) {
                report(file, event.name, "Convergent event '${event.name.text}' requires a variant")
            }
            checkEvent(component, event, env, scope)?.let { events[it.name] = it }
        }

        abstract?.let { abs ->
            val refined = events.values.flatMap { it.refines.map { r -> r.name } }.toSet() + INITIALISATION
            for (event in abs.events.keys - refined) {
                report(file, unit.name, "Abstract event '$event' is not refined", DiagnosticSeverity.Warning)
            }
        }

        val info = MachineInfo(name, component, env, seen.toList(), abstract, variables, invariants, variant, events)
        machines[name] = info
        return info
    }

    private class MachineScope(
        val contextNames: Set<String>,
        val variables: List<String>,
        val abstract: MachineInfo?,
    )

    private fun checkEvent(
        component: Component,
        event: AEvent,
        machineEnv: ITypeEnvironmentBuilder,
        scope: MachineScope,
    ): EventInfo? {
        val file = component.file
        val name = event.name.text
        val isInitialisation = name == INITIALISATION
        val abstract = scope.abstract
        val refinement = event.refinement
        val extended = refinement is AExtendedEventRefinement

        val references: List<TIdentifierLiteral> =
            when (refinement) {
                is AExtendedEventRefinement -> listOf(refinement.name)
                is ARefinesEventRefinement -> refinement.names
                else -> emptyList()
            }
        val refines = mutableListOf<EventInfo>()
        if (abstract != null) {
            for (reference in references) {
                val target = abstract.events[reference.text]
                if (target == null) report(file, reference, "The abstract machine has no event '${reference.text}'") else refines += target
            }
            if (isInitialisation && references.isEmpty()) abstract.events[INITIALISATION]?.let { refines += it }
        } else if (references.isNotEmpty()) {
            report(file, references.first(), "Event '$name' refines '${references.first().text}', but the machine refines no machine")
        }

        if (isInitialisation && event.parameters.isNotEmpty()) report(file, event.name, "INITIALISATION must not have parameters")
        if (isInitialisation && event.guards.isNotEmpty()) {
            val first =
                when (val g = event.guards.first()) {
                    is AGuard -> g.name
                    is ADerivedGuard -> g.name
                    else -> event.name
                }
            report(file, first, "INITIALISATION must not have guards")
        }

        if (refines.size > 1) {
            fun actions(e: EventInfo) = e.actions.map { it.label to it.formula.toString() }.toSet()
            val first = refines.first()
            refines.drop(1).firstOrNull { actions(it) != actions(first) }?.let {
                report(file, event.name, "Merged events '${first.name}' and '${it.name}' have different actions")
            }
        }

        val env = machineEnv.makeBuilder()
        val inherited = if (extended) refines.firstOrNull() else null
        val parameters = LinkedHashMap<String, Type>()
        inherited?.parameters?.forEach { (p, type) ->
            env.addName(p, type)
            parameters[p] = type
        }
        val declared = HashMap<String, String>()
        (scope.contextNames + scope.abstract?.allVariables.orEmpty() + scope.variables).forEach { declared[it] = it }
        val parameterTokens =
            event.parameters.filterIsInstance<AParameter>().map { it.name }.filter { token ->
                when {
                    token.text in parameters -> {
                        report(file, token, "'${token.text}' is already declared")
                        false
                    }

                    else -> {
                        declare(file, token, declared, name)
                    }
                }
            }

        val variables = scope.variables.toSet()
        val guardScope = scope.contextNames + variables + parameters.keys + parameterTokens.map { it.text }
        val labels = mutableSetOf<String>()
        inherited?.guards?.forEach { labels += it.label }
        inherited?.actions?.forEach { labels += it.label }

        val guards = inherited?.guards.orEmpty().toMutableList()
        var typed = true
        for (guard in event.guards) {
            val (label, text, theorem) =
                when (guard) {
                    is AGuard -> Triple(guard.name, guard.predicate, false)
                    is ADerivedGuard -> Triple(guard.name, guard.predicate, true)
                    else -> continue
                }
            checkLabel(file, label, labels)
            val predicate = checkFormula(file, label, text, Kind.PREDICATE, env, guardScope) as Predicate?
            if (predicate == null) typed = false else guards += Labeled(labelText(label), label, predicate, theorem, located(text))
        }
        for (parameter in parameterTokens) {
            if (env.contains(parameter.text)) {
                parameters[parameter.text] = env.getType(parameter.text)
            } else if (typed) {
                report(file, parameter, "Parameter '${parameter.text}' has no type; add a guard such as ${parameter.text} ∈ S")
            }
        }

        // Actions read the old state; the initialisation has none.
        val actions = inherited?.actions.orEmpty().toMutableList()
        val assigned = actions.flatMap { a -> a.formula.assignedIdentifiers.map { it.name } }.toMutableSet()
        for (action in event.actions.filterIsInstance<AAction>()) {
            checkLabel(file, action.name, labels)
            val assignment =
                checkFormula(file, action.name, action.action, Kind.ASSIGNMENT, env.makeBuilder(), guardScope) as Assignment? ?: continue
            var valid = true
            for (identifier in assignment.assignedIdentifiers) {
                if (identifier.name !in variables) {
                    report(
                        file,
                        action.action,
                        identifier.sourceLocation,
                        "'${identifier.name}' is not a variable",
                        DiagnosticSeverity.Error,
                    )
                    valid = false
                } else if (!assigned.add(identifier.name)) {
                    report(file, action.name, "'${identifier.name}' is assigned in more than one action")
                    valid = false
                }
            }
            if (isInitialisation) {
                for (identifier in assignment.usedIdentifiers.filter {
                    it.name in variables ||
                        it.name in scope.abstract?.allVariables.orEmpty()
                }) {
                    report(
                        file,
                        action.action,
                        identifier.sourceLocation,
                        "Identifier '${identifier.name}' has not been declared",
                        DiagnosticSeverity.Error,
                    )
                    valid = false
                }
            }
            if (valid) actions += Labeled(labelText(action.name), action.name, assignment, source = located(action.action))
        }
        if (isInitialisation) {
            for (variable in scope.variables - assigned) {
                report(file, event.name, "Variable '$variable' is not initialised; it may take any value", DiagnosticSeverity.Warning)
            }
        }

        // Witnesses relate abstract parameters and disappearing variables to the concrete state.
        val abstractVariables = abstract?.variables.orEmpty().toSet()
        val disappearing = abstractVariables - variables
        val abstractParameters = refines.flatMap { it.parameters.entries }.associate { it.key to it.value }
        val disappearingParameters = if (extended) emptyMap() else abstractParameters - parameters.keys
        val nondeterministic =
            refines
                .flatMap { it.actions }
                .filter { it.formula.tag != Formula.BECOMES_EQUAL_TO }
                .flatMap { a -> a.formula.assignedIdentifiers.map { it.name } }
                .filter { it in disappearing }
                .toSet()

        val witnessEnv = env.makeBuilder()
        val witnessScope = (guardScope + abstractVariables + scope.abstract?.allVariables.orEmpty()).toMutableSet()
        for (variable in variables + disappearing) {
            if (witnessEnv.contains(variable)) {
                witnessEnv.addName("$variable'", witnessEnv.getType(variable))
                witnessScope += "$variable'"
            }
        }
        disappearingParameters.forEach { (p, type) ->
            if (!witnessEnv.contains(p)) witnessEnv.addName(p, type)
            witnessScope += p
        }
        val witnesses = mutableListOf<Labeled<Predicate>>()
        val witnessed = mutableSetOf<String>()
        for (witness in event.witnesses.filterIsInstance<AWitness>()) {
            val label = labelText(witness.name)
            val target = label.removeSuffix("'")
            val valid = if (label.endsWith("'")) target in disappearing else label in disappearingParameters
            if (!valid) {
                report(file, witness.name, "Witness '$label' is not an abstract parameter or disappearing variable")
                continue
            }
            witnessed += label
            val predicate =
                checkFormula(
                    file,
                    witness.name,
                    witness.predicate,
                    Kind.PREDICATE,
                    witnessEnv.makeBuilder(),
                    witnessScope,
                ) as Predicate?
            predicate?.let { witnesses += Labeled(label, witness.name, it, source = located(witness.predicate)) }
        }
        for (p in disappearingParameters.keys - witnessed) {
            report(file, event.name, "Witness for '$p' missing; the default witness ⊤ is used", DiagnosticSeverity.Warning)
        }
        for (x in nondeterministic.map { "$it'" } - witnessed) {
            report(file, event.name, "Witness for '$x' missing; the default witness ⊤ is used", DiagnosticSeverity.Warning)
        }

        val convergence =
            when (event.convergence) {
                is AConvergentConvergence -> Convergence.CONVERGENT
                is AAnticipatedConvergence -> Convergence.ANTICIPATED
                else -> Convergence.ORDINARY
            }
        return EventInfo(name, event.name, convergence, refines, extended, parameters, guards, actions, witnesses)
    }

    /** Declares [token] in [origins]; reports it if the name is taken. */
    private fun declare(
        file: File,
        token: TIdentifierLiteral,
        origins: MutableMap<String, String>,
        origin: String,
    ): Boolean {
        if (token.text in origins) {
            report(file, token, "'${token.text}' is already declared")
            return false
        }
        origins[token.text] = origin
        return true
    }

    private fun labelText(label: TLabel) = label.text.removePrefix("@")

    private fun located(formula: TFormula) = formula.takeIf { it !in translated }

    private fun checkLabel(
        file: File,
        label: TLabel,
        labels: MutableSet<String>,
    ) {
        if (!labels.add(labelText(label))) report(file, label, "Label '${labelText(label)}' is already used")
    }

    /**
     * Parses and type-checks one formula, accepting ASCII notation like Rodin's
     * editors do. Inferred types are added to [env], which is how constants,
     * variables and parameters get their types.
     *
     * @return the type-checked formula, or null after reporting its problems
     */
    private fun checkFormula(
        file: File,
        label: TLabel?,
        formula: TFormula,
        kind: Kind,
        env: ITypeEnvironmentBuilder,
        scope: Set<String>,
    ): Formula<*>? {
        fun parse(text: String): IParseResult =
            when (kind) {
                Kind.PREDICATE -> factory.parsePredicate(text, null)
                Kind.EXPRESSION -> factory.parseExpression(text, null)
                Kind.ASSIGNMENT -> factory.parseAssignment(text, null)
            }
        var result = parse(formula.text)
        if (result.hasProblem()) {
            val unicode = parse(UnicodeTranslator.toRodinUnicode(formula.text))
            if (unicode.problems.size < result.problems.size) {
                result = unicode
                translated += formula
            }
        }
        result.problems.forEach { report(file, formula, it) }
        if (result.hasProblem()) return null

        val parsed: Formula<*> =
            when (kind) {
                Kind.PREDICATE -> result.parsedPredicate
                Kind.EXPRESSION -> result.parsedExpression
                Kind.ASSIGNMENT -> result.parsedAssignment
            }
        val undeclared = parsed.freeIdentifiers.filter { it.name !in scope }
        for (identifier in undeclared) {
            report(
                file,
                formula,
                identifier.sourceLocation,
                "Identifier '${identifier.name}' has not been declared",
                DiagnosticSeverity.Error,
            )
        }
        if (undeclared.isNotEmpty()) return null

        val typeCheck = parsed.typeCheck(env)
        typeCheck.problems.forEach { report(file, formula, it) }
        if (typeCheck.hasProblem()) {
            if (label != null && typeCheck.problems.none { it.sourceLocation != null }) {
                report(file, label, "Type error in ${labelText(label)}")
            }
            return null
        }
        env.addAll(typeCheck.inferredEnvironment)
        return parsed
    }

    // ----------------------------------------------------------------------
    // Diagnostics

    private fun report(
        file: File,
        formula: TFormula,
        problem: ASTProblem,
    ) {
        val severity = if (problem.isError) DiagnosticSeverity.Error else DiagnosticSeverity.Warning
        report(file, formula, problem.sourceLocation, problem.toString(), severity)
    }

    /** Reports a problem at a [location] inside [formula], whose text may span lines. */
    private fun report(
        file: File,
        formula: TFormula,
        location: SourceLocation?,
        message: String,
        severity: DiagnosticSeverity,
    ) {
        val text = formula.text
        val start = location?.start ?: 0
        val end = (location?.end ?: (text.length - 1)) + 1
        diagnostics +=
            Diagnostic(
                Range(offsetToPosition(formula, start), offsetToPosition(formula, end.coerceAtMost(text.length))),
                message,
                severity,
                file.toURI().path,
            )
    }

    private fun offsetToPosition(
        formula: TFormula,
        offset: Int,
    ): Position {
        var line = formula.line - 1
        var column = formula.pos - 1
        for (char in formula.text.substring(0, offset.coerceIn(0, formula.text.length))) {
            if (char == '\n') {
                line++
                column = 0
            } else {
                column++
            }
        }
        return Position(line, column)
    }

    private fun report(
        file: File,
        token: Token,
        message: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.Error,
    ) = report(file, token.line, token.pos, token.text.length, message, severity)

    private fun report(
        file: File,
        line: Int,
        pos: Int,
        length: Int,
        message: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.Error,
    ) {
        diagnostics += diagnostic(file, line, pos, length, message, severity)
    }

    // ----------------------------------------------------------------------
    // Translation to prob-java

    /** Builds the prob-java model of the checked components; returns it with the main component. */
    fun toModel(empty: EventBModel): Pair<EventBModel, AbstractElement> {
        val noExtensions = emptySet<org.eventb.core.ast.extension.IFormulaExtension>()
        val contextModels = HashMap<String, Context>()
        val machineModels = HashMap<String, EventBMachine>()
        var model = empty

        fun context(info: ContextInfo): Context {
            contextModels[info.name]?.let { return it }
            val extended = info.extended.map { context(it) }
            val built =
                Context(info.name)
                    .withExtends(ModelElementList(extended))
                    .withSets(ModelElementList(info.sets.map { ProBSet(EventB(it, noExtensions)) }))
                    .withConstants(ModelElementList(info.constants.map { EventBConstant(it, false, null) }))
                    .withAxioms(
                        ModelElementList(info.axioms.map { EventBAxiom(it.label, it.formula.toString(), it.theorem, noExtensions) }),
                    )
            contextModels[info.name] = built
            model = model.addContext(built)
            extended.forEach { model = model.addRelationship(info.name, it.name, ERefType.EXTENDS) }
            return built
        }

        fun machine(info: MachineInfo): EventBMachine {
            machineModels[info.name]?.let { return it }
            val abstract = info.abstract?.let { machine(it) }
            // A machine sees the contexts of its abstractions too; ProB wants the ones named directly.
            val sees = info.contexts.filter { c -> info.contexts.none { other -> other !== c && c in other.closure() } }.map { context(it) }

            // Extended events are flattened, as Rodin's static checker does.
            val events =
                info.events.values.map { event ->
                    val type =
                        when (event.convergence) {
                            Convergence.CONVERGENT -> Event.EventType.CONVERGENT
                            Convergence.ANTICIPATED -> Event.EventType.ANTICIPATED
                            Convergence.ORDINARY -> Event.EventType.ORDINARY
                        }
                    val inheritance = if (event.refines.isEmpty()) Event.Inheritance.NONE else Event.Inheritance.REFINES
                    Event(event.name, type, inheritance)
                        .withParentEvent(event.refines.firstOrNull()?.let { abstract?.getEvent(it.name) })
                        .withParameters(ModelElementList(event.parameters.keys.map { EventParameter(it) }))
                        .withGuards(
                            ModelElementList(event.guards.map { EventBGuard(it.label, it.formula.toString(), it.theorem, noExtensions) }),
                        ).withWitnesses(ModelElementList(event.witnesses.map { Witness(it.label, it.formula.toString(), noExtensions) }))
                        .withActions(ModelElementList(event.actions.map { EventBAction(it.label, it.formula.toString(), noExtensions) }))
                }

            var built =
                EventBMachine(info.name)
                    .withRefinesMachine(abstract)
                    .withSees(ModelElementList(sees))
                    .withVariables(ModelElementList(info.variables.map { EventBVariable(it, null) }))
                    .withInvariants(
                        ModelElementList(
                            info.invariants.map { EventBInvariant(it.label, it.formula.toString(), it.theorem, noExtensions) },
                        ),
                    ).withEvents(ModelElementList(events))
            info.variant?.let { built = built.withVariant(Variant(it.formula.toString(), noExtensions)) }

            machineModels[info.name] = built
            model = model.addMachine(built)
            abstract?.let { model = model.addRelationship(info.name, it.name, ERefType.REFINES) }
            sees.forEach { model = model.addRelationship(info.name, it.name, ERefType.SEES) }
            return built
        }

        val mainComponent: AbstractElement =
            when (val info = mainInfo) {
                is MachineInfo -> machine(info)
                is ContextInfo -> context(info)
                else -> error("the model has not been checked")
            }
        return model to mainComponent
    }
}

fun diagnostic(
    file: File,
    line: Int,
    pos: Int,
    length: Int,
    message: String,
    severity: DiagnosticSeverity,
): Diagnostic {
    val start = Position((line - 1).coerceAtLeast(0), (pos - 1).coerceAtLeast(0))
    return Diagnostic(
        Range(start, Position(start.line, start.character + length.coerceAtLeast(1))),
        message,
        severity,
        file.toURI().path,
    )
}
