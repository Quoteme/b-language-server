package b.language.server.analysis.eventB

import b.language.server.analysis.Severity
import b.language.server.analysis.prob.ProBSession
import b.language.server.analysis.prob.explanation
import b.language.server.analysis.prob.animation.AnimationLoad
import b.language.server.analysis.prob.animation.AnimationModel
import b.language.server.analysis.prob.animation.AnimationSources
import b.language.server.analysis.prob.animation.DeclarationKind
import b.language.server.analysis.prob.animation.LabeledPredicate
import b.language.server.project.Location
import b.language.server.project.SourceLines
import b.language.server.project.SourcePosition
import b.language.server.project.eventB.EventBProject
import de.be4.eventb.core.parser.node.Token
import de.prob.model.eventb.EventBModel
import de.prob.statespace.StateSpace
import java.io.File

/** An Event-B component to animate; it is checked first, since ProB needs a well-typed model. */
class EventBAnimationModel(
    private val project: EventBProject,
) : AnimationModel {
    override val file: File get() = project.mainFile

    override fun load(
        session: ProBSession,
        stateSpace: StateSpace,
    ): AnimationLoad {
        val result = StaticChecker(project).check()
        val error = result.problems.firstOrNull { it.severity == Severity.ERROR }
        if (error != null) return AnimationLoad.Failed("The model has errors; fix them first: ${error.message}")
        val main = result.main ?: return AnimationLoad.Failed("ProB could not load the model")
        return ProBTranslation.of(main, session.instance(EventBModel::class.java)).loadInto(stateSpace).fold(
            { failure -> AnimationLoad.Failed(failure.explanation) },
            { AnimationLoad.Loaded(EventBAnimationSources(main)) },
        )
    }
}

/** Where the parts of a checked Event-B model are written. */
class EventBAnimationSources(
    private val main: CheckedComponent,
) : AnimationSources {
    private val machine = main as? CheckedMachine

    override val name: String get() = main.name
    override val isContext: Boolean get() = main is CheckedContext
    override val constants: List<String> get() = main.contexts.flatMap { it.constants }
    override val variables: List<String> get() = machine?.allVariables?.toList().orEmpty()

    override val axioms: List<LabeledPredicate>
        get() = main.contexts.flatMap { c -> c.axioms.filter { !it.theorem }.map { predicate(it, c.file) } }

    override val invariants: List<LabeledPredicate>
        get() = machine?.let { m -> m.invariants.filter { !it.theorem }.map { predicate(it, m.file) } }.orEmpty()

    override fun event(name: String): Location? = machine?.let { m -> m.events[name]?.let { at(m.file, it.token) } }

    override fun guards(event: String): List<LabeledPredicate> {
        val info = machine?.events?.get(event) ?: return emptyList()
        if (info.parameters.isNotEmpty()) return emptyList()
        return info.guards.filter { !it.theorem }.map { LabeledPredicate(it.label, it.formula.toString()) }
    }

    override fun declaration(
        name: String,
        kind: DeclarationKind,
    ): Location? {
        val keyword =
            when (kind) {
                DeclarationKind.CONSTANT -> "constants"
                DeclarationKind.VARIABLE -> "variables"
                DeclarationKind.INVARIANT_CLAUSE -> return null
            }
        val files = (listOfNotNull(machine?.file) + main.contexts.map { it.file }).distinct()
        return files.firstNotNullOfOrNull { SourceLines(it).find(SourceLines.identifier(name), keyword) }
    }

    private fun predicate(
        labeled: LabeledFormula<*>,
        file: File,
    ) = LabeledPredicate(labeled.label, labeled.formula.toString(), at(file, labeled.token))

    private fun at(
        file: File,
        token: Token,
    ) = Location.at(file, SourcePosition(token.line - 1, token.pos - 1))
}
