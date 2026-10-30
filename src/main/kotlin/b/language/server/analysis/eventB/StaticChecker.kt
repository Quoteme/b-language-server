package b.language.server.analysis.eventB

import b.language.server.analysis.Problem
import b.language.server.analysis.Severity
import b.language.server.analysis.prob.Transitions.INITIALISATION
import b.language.server.project.Location
import b.language.server.project.SourcePosition
import b.language.server.project.SourceRange
import b.language.server.project.eventB.EventBProject
import b.language.server.project.eventB.EventBProject.Component
import b.language.server.project.eventB.EventBProject.ComponentKind
import b.language.server.project.eventB.EventBProject.Loading
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
import de.be4.eventb.core.parser.node.PAxiom
import de.be4.eventb.core.parser.node.PGuard
import de.be4.eventb.core.parser.node.PInvariant
import de.be4.eventb.core.parser.node.TFormula
import de.be4.eventb.core.parser.node.TIdentifierLiteral
import de.be4.eventb.core.parser.node.TLabel
import de.be4.eventb.core.parser.node.Token
import de.prob.unicode.UnicodeTranslator
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

/**
 * Checks an Event-B project with the rules of Rodin's static checker (Rodin Handbook 3.2), using
 * Rodin's AST library to parse and type-check the formulas. Each instance checks once.
 */
class StaticChecker(
    private val project: EventBProject,
) {
    /** The checked main component, if the checks got that far, and the problems found. */
    data class Result(
        val main: CheckedComponent?,
        val problems: List<Problem>,
    ) {
        val hasErrors: Boolean get() = problems.any { it.severity == Severity.ERROR }
    }

    private enum class Kind { PREDICATE, EXPRESSION, ASSIGNMENT }

    /** A labelled predicate as written: an axiom, invariant or guard, which may be a theorem. */
    private class Written(
        val label: TLabel,
        val text: TFormula,
        val theorem: Boolean,
    )

    private class MachineScope(
        val contextNames: Set<String>,
        val variables: List<String>,
        val abstract: CheckedMachine?,
    )

    private val factory = FormulaFactory.getDefault()
    private val problems = mutableListOf<Problem>()
    private val contexts = HashMap<String, CheckedContext?>()
    private val machines = HashMap<String, CheckedMachine?>()

    /** Formulas parsed only after translating ASCII to Unicode. */
    private val translated = HashSet<TFormula>()

    /** Parses and checks the main file and every component it depends on. */
    fun check(): Result {
        val main =
            when (val loading = project.loadMain()) {
                is Loading.Loaded -> loading.component
                is Loading.Failed -> return Result(null, problems + loading.errors.map { it.toProblem() })
            }
        val checked =
            when (main.kind) {
                ComponentKind.MACHINE -> checkMachine(main.name.text)
                ComponentKind.CONTEXT -> checkContext(main.name.text)
            }
        return Result(checked, problems.toList())
    }

    private fun EventBProject.LoadError.toProblem() = Problem(location, message, Severity.ERROR)

    private fun resolve(
        from: Component,
        reference: TIdentifierLiteral,
    ): Component? =
        when (val loading = project.resolve(from, reference)) {
            is Loading.Loaded -> {
                loading.component
            }

            is Loading.Failed -> {
                problems += loading.errors.map { it.toProblem() }
                null
            }
        }

    private fun checkContext(name: String): CheckedContext? {
        if (name in contexts) return contexts[name]
        contexts[name] = null // guards against cyclic extends
        val component = project.component(name) ?: return null
        val unit = component.unit as? AContextParseUnit ?: return null
        val file = component.file

        val env = factory.makeTypeEnvironment()
        val origins = LinkedHashMap<String, String>()
        val extended = mutableListOf<CheckedContext>()
        for (reference in unit.extendsNames) {
            val target = resolve(component, reference) ?: continue
            if (target.kind != ComponentKind.CONTEXT) {
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
        val axioms = mutableListOf<LabeledFormula<Predicate>>()
        var typed = true
        val labels = mutableSetOf<String>()
        for (axiom in unit.axioms.mapNotNull { written(it) }) {
            checkLabel(file, axiom.label, labels)
            val predicate = checkFormula(file, axiom.label, axiom.text, Kind.PREDICATE, env, scope) as Predicate?
            if (predicate == null) typed = false else axioms += labeled(axiom.label, predicate, axiom.text, axiom.theorem)
        }
        // Missing types are only worth reporting if the axioms themselves were fine.
        for (constant in constantTokens.takeIf { typed }.orEmpty()) {
            if (!env.contains(constant.text)) {
                report(file, constant, "Constant '${constant.text}' has no type; add an axiom such as ${constant.text} ∈ S")
            }
        }
        val info = CheckedContext(name, component, env, origins, sets, constantTokens.map { it.text }, extended, axioms)
        contexts[name] = info
        return info
    }

    private fun checkMachine(name: String): CheckedMachine? {
        if (name in machines) return machines[name]
        machines[name] = null // guards against cyclic refinement
        val component = project.component(name) ?: return null
        val unit = component.unit as? AMachineParseUnit ?: return null
        val file = component.file

        val env = factory.makeTypeEnvironment()
        var abstract: CheckedMachine? = null
        unit.refinesNames.firstOrNull()?.let { refined ->
            val target = resolve(component, refined) ?: return@let
            if (target.kind != ComponentKind.MACHINE) {
                report(file, refined, "'${refined.text}' is not a machine")
                return@let
            }
            abstract = checkMachine(refined.text)
            abstract?.let { env.addAll(it.env) }
        }

        val seen = LinkedHashSet<CheckedContext>(abstract?.contexts.orEmpty())
        for (reference in unit.seenNames) {
            val target = resolve(component, reference) ?: continue
            if (target.kind != ComponentKind.CONTEXT) {
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
        val invariants = mutableListOf<LabeledFormula<Predicate>>()
        val labels = mutableSetOf<String>()
        var typed = true
        for (invariant in unit.invariants.mapNotNull { written(it) }) {
            checkLabel(file, invariant.label, labels)
            val predicate =
                checkFormula(
                    file,
                    invariant.label,
                    invariant.text,
                    Kind.PREDICATE,
                    env,
                    contextNames + allVariables,
                ) as Predicate?
            if (predicate == null) {
                typed = false
            } else {
                invariants += labeled(invariant.label, predicate, invariant.text, invariant.theorem)
            }
        }
        for (variable in variableTokens.takeIf { typed }.orEmpty()) {
            if (!env.contains(variable.text)) {
                report(file, variable, "Variable '${variable.text}' has no type; add an invariant such as ${variable.text} ∈ S")
            }
        }

        val variant =
            (unit.variant as? AVariant)?.let { node ->
                val expression =
                    checkFormula(file, null, node.expression, Kind.EXPRESSION, env.makeBuilder(), contextNames + variables) as Expression?
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
                            Severity.ERROR,
                        )
                        null
                    }

                    else -> {
                        LabeledFormula(LabeledFormula.VARIANT, node.expression, expression, source = located(node.expression))
                    }
                }
            }

        val scope = MachineScope(contextNames, variables, abstract)
        val events = LinkedHashMap<String, CheckedEvent>()
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
                report(file, unit.name, "Abstract event '$event' is not refined", Severity.WARNING)
            }
        }

        val info = CheckedMachine(name, component, env, seen.toList(), abstract, variables, invariants, variant, events)
        machines[name] = info
        return info
    }

    private fun checkEvent(
        component: Component,
        event: AEvent,
        machineEnv: ITypeEnvironmentBuilder,
        scope: MachineScope,
    ): CheckedEvent? {
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
        val refines = mutableListOf<CheckedEvent>()
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
            report(file, written(event.guards.first())?.label ?: event.name, "INITIALISATION must not have guards")
        }

        if (refines.size > 1) {
            fun actions(e: CheckedEvent) = e.actions.map { it.label to it.formula.toString() }.toSet()
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
                if (token.text in parameters) {
                    report(file, token, "'${token.text}' is already declared")
                    false
                } else {
                    declare(file, token, declared, name)
                }
            }

        val variables = scope.variables.toSet()
        val guardScope = scope.contextNames + variables + parameters.keys + parameterTokens.map { it.text }
        val labels = mutableSetOf<String>()
        inherited?.guards?.forEach { labels += it.label }
        inherited?.actions?.forEach { labels += it.label }

        val guards = inherited?.guards.orEmpty().toMutableList()
        var typed = true
        for (guard in event.guards.mapNotNull { written(it) }) {
            checkLabel(file, guard.label, labels)
            val predicate = checkFormula(file, guard.label, guard.text, Kind.PREDICATE, env, guardScope) as Predicate?
            if (predicate == null) typed = false else guards += labeled(guard.label, predicate, guard.text, guard.theorem)
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
                    report(file, action.action, identifier.sourceLocation, "'${identifier.name}' is not a variable", Severity.ERROR)
                    valid = false
                } else if (!assigned.add(identifier.name)) {
                    report(file, action.name, "'${identifier.name}' is assigned in more than one action")
                    valid = false
                }
            }
            if (isInitialisation) {
                val stateIdentifiers = variables + scope.abstract?.allVariables.orEmpty()
                for (identifier in assignment.usedIdentifiers.filter { it.name in stateIdentifiers }) {
                    report(
                        file,
                        action.action,
                        identifier.sourceLocation,
                        "Identifier '${identifier.name}' has not been declared",
                        Severity.ERROR,
                    )
                    valid = false
                }
            }
            if (valid) actions += labeled(action.name, assignment, action.action)
        }
        if (isInitialisation) {
            for (variable in scope.variables - assigned) {
                report(file, event.name, "Variable '$variable' is not initialised; it may take any value", Severity.WARNING)
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
        val witnesses = mutableListOf<LabeledFormula<Predicate>>()
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
                checkFormula(file, witness.name, witness.predicate, Kind.PREDICATE, witnessEnv.makeBuilder(), witnessScope) as Predicate?
            predicate?.let { witnesses += LabeledFormula(label, witness.name, it, source = located(witness.predicate)) }
        }
        for (p in disappearingParameters.keys - witnessed) {
            report(file, event.name, "Witness for '$p' missing; the default witness ⊤ is used", Severity.WARNING)
        }
        for (x in nondeterministic.map { "$it'" } - witnessed) {
            report(file, event.name, "Witness for '$x' missing; the default witness ⊤ is used", Severity.WARNING)
        }

        val convergence =
            when (event.convergence) {
                is AConvergentConvergence -> Convergence.CONVERGENT
                is AAnticipatedConvergence -> Convergence.ANTICIPATED
                else -> Convergence.ORDINARY
            }
        return CheckedEvent(name, event.name, convergence, refines, extended, parameters, guards, actions, witnesses)
    }

    private fun written(axiom: PAxiom): Written? =
        when (axiom) {
            is AAxiom -> Written(axiom.name, axiom.predicate, false)
            is ADerivedAxiom -> Written(axiom.name, axiom.predicate, true)
            else -> null
        }

    private fun written(invariant: PInvariant): Written? =
        when (invariant) {
            is AInvariant -> Written(invariant.name, invariant.predicate, false)
            is ADerivedInvariant -> Written(invariant.name, invariant.predicate, true)
            else -> null
        }

    private fun written(guard: PGuard): Written? =
        when (guard) {
            is AGuard -> Written(guard.name, guard.predicate, false)
            is ADerivedGuard -> Written(guard.name, guard.predicate, true)
            else -> null
        }

    private fun <T : Formula<T>> labeled(
        label: TLabel,
        formula: T,
        text: TFormula,
        theorem: Boolean = false,
    ) = LabeledFormula(labelText(label), label, formula, theorem, located(text))

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
     * Parses and type-checks one formula, accepting ASCII notation like Rodin's editors do.
     * Inferred types are added to [env], which is how constants, variables and parameters get their
     * types.
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
            report(file, formula, identifier.sourceLocation, "Identifier '${identifier.name}' has not been declared", Severity.ERROR)
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

    private fun report(
        file: File,
        formula: TFormula,
        problem: ASTProblem,
    ) = report(file, formula, problem.sourceLocation, problem.toString(), if (problem.isError) Severity.ERROR else Severity.WARNING)

    /** Reports a problem at a [location] inside [formula], whose text may span lines. */
    private fun report(
        file: File,
        formula: TFormula,
        location: SourceLocation?,
        message: String,
        severity: Severity,
    ) {
        val text = formula.text
        val start = location?.start ?: 0
        val end = (location?.end ?: (text.length - 1)) + 1
        val range = SourceRange(positionIn(formula, start), positionIn(formula, end.coerceAtMost(text.length)))
        problems += Problem(Location(file, range), message, severity)
    }

    private fun positionIn(
        formula: TFormula,
        offset: Int,
    ): SourcePosition {
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
        return SourcePosition(line, column)
    }

    private fun report(
        file: File,
        token: Token,
        message: String,
        severity: Severity = Severity.ERROR,
    ) {
        problems += Problem(Location.ofToken(file, token.line, token.pos, token.text.length), message, severity)
    }
}
