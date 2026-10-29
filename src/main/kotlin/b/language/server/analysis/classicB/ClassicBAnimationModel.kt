package b.language.server.analysis.classicB

import b.language.server.analysis.prob.ProBSession
import b.language.server.analysis.prob.explanation
import b.language.server.analysis.prob.animation.AnimationLoad
import b.language.server.analysis.prob.animation.AnimationModel
import b.language.server.analysis.prob.animation.AnimationSources
import b.language.server.analysis.prob.animation.DeclarationKind
import b.language.server.analysis.prob.animation.LabeledPredicate
import b.language.server.project.Location
import b.language.server.project.classicB.ClassicBProject
import b.language.server.project.classicB.ClassicBProject.Section
import de.prob.statespace.StateSpace
import java.io.File

/** A classical B machine to animate. */
class ClassicBAnimationModel(
    private val project: ClassicBProject,
) : AnimationModel {
    override val file: File get() = project.mainFile

    override fun load(
        session: ProBSession,
        stateSpace: StateSpace,
    ): AnimationLoad {
        return ClassicBModel(session, project).loadInto(stateSpace).fold(
            { failure -> AnimationLoad.Failed(failure.explanation) },
            { AnimationLoad.Loaded(ClassicBAnimationSources(project)) },
        )
    }
}

/** Where things are declared in a classical B machine, found by searching its text; ProB knows the names. */
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

    override fun declaration(
        name: String,
        kind: DeclarationKind,
    ): Location? =
        when (kind) {
            DeclarationKind.CONSTANT -> project.declaration(name, Section.CONSTANTS)
            DeclarationKind.VARIABLE -> project.declaration(name, Section.VARIABLES)
            DeclarationKind.INVARIANT_CLAUSE -> project.declaration(name, Section.INVARIANT)
        }
}
