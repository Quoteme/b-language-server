package b.language.server.analysis.prob.animation

import b.language.server.analysis.prob.ProBSession
import b.language.server.project.Location
import de.prob.statespace.StateSpace
import java.io.File

/** A model to animate, and how to load it. */
interface AnimationModel {
    val file: File

    /** Loads the model into [stateSpace], which belongs to [session]. */
    fun load(
        session: ProBSession,
        stateSpace: StateSpace,
    ): AnimationLoad
}

sealed interface AnimationLoad {
    /** The model is loaded; [sources] tell where its parts are written. */
    data class Loaded(
        val sources: AnimationSources,
    ) : AnimationLoad

    /** The model cannot be animated, for the given [reason]. */
    data class Failed(
        val reason: String,
    ) : AnimationLoad
}

/** A labelled predicate whose truth the animation shows, such as an invariant or a guard. */
data class LabeledPredicate(
    val label: String,
    val formula: String,
    val location: Location? = null,
)

enum class DeclarationKind { CONSTANT, VARIABLE, INVARIANT_CLAUSE }

/** Where the parts of an animated model are declared, so the client can jump to them. */
interface AnimationSources {
    val name: String

    /** A context has constants and axioms, but no variables and events. */
    val isContext: Boolean

    /** The constants, or null if ProB's names are to be used. */
    val constants: List<String>?

    /** The variables, or null if ProB's names are to be used. */
    val variables: List<String>?

    val axioms: List<LabeledPredicate>
    val invariants: List<LabeledPredicate>

    fun event(name: String): Location?

    /** The guards of [event], if its truth can be shown without parameters. */
    fun guards(event: String): List<LabeledPredicate>

    fun declaration(
        name: String,
        kind: DeclarationKind,
    ): Location?
}
