package b.language.server.analysis.classicB

import b.language.server.analysis.prob.ProBSession
import b.language.server.analysis.prob.animation.AnimationLoad
import b.language.server.analysis.prob.animation.AnimationModel
import b.language.server.analysis.prob.animation.AnimationSources
import b.language.server.analysis.prob.animation.LabeledPredicate
import b.language.server.analysis.prob.explanation
import b.language.server.project.Location
import b.language.server.project.classicB.ClassicBProject
import de.prob.statespace.StateSpace

/** A classical B machine to animate. */
class ClassicBAnimationModel(
    override val project: ClassicBProject,
) : AnimationModel {
    override fun load(
        session: ProBSession,
        stateSpace: StateSpace,
    ): AnimationLoad =
        ClassicBModel(session, project).loadInto(stateSpace).fold(
            { failure -> AnimationLoad.Failed(failure.explanation) },
            { AnimationLoad.Loaded(ClassicBAnimationSources(project)) },
        )
}

/** A classical B machine adds nothing to what ProB reports but where its operations are written. */
class ClassicBAnimationSources(
    private val project: ClassicBProject,
) : AnimationSources {
    override val name: String get() = project.mainFile.nameWithoutExtension
    override val isContext: Boolean get() = false
    override val constants: List<String>? get() = null
    override val variables: List<String>? get() = null
    override val axioms: List<LabeledPredicate> get() = emptyList()
    override val invariants: List<LabeledPredicate> get() = emptyList()

    override fun event(name: String): Location? = project.operation(name)

    override fun guards(event: String): List<LabeledPredicate> = emptyList()
}
