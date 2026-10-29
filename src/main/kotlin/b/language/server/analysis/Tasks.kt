package b.language.server.analysis

import b.language.server.analysis.prob.animation.AnimationModel
import b.language.server.config.Settings
import b.language.server.project.Project
import b.language.server.project.SourcePosition

/** Checks a project and reports its problems. */
interface Checker<in P : Project> {
    fun check(
        project: P,
        settings: Settings,
    ): List<Problem>
}

/** Documents what is under the cursor. */
interface HoverProvider<in P : Project> {
    fun hover(
        project: P,
        at: SourcePosition,
    ): Markdown?
}

/** Completes the word left of the cursor. */
interface CompletionProvider<in P : Project> {
    fun complete(
        project: P,
        at: SourcePosition,
    ): List<CompletionCandidate>
}

/** Explores the states of a model for deadlocks and invariant violations. */
interface ModelChecker<in P : Project> {
    fun modelCheck(
        project: P,
        settings: Settings,
    ): ModelCheckReport
}

/** The proof obligations of a project, from its last check. */
interface ProofObligationProvider<in P : Project> {
    fun proofObligations(project: P): List<ProofObligation>
}

/** Prepares a project to be animated. */
interface AnimationProvider<in P : Project> {
    fun animationModel(project: P): AnimationModel
}

/** Everything the server asks of the analysis of one kind of project. */
interface Analysis<in P : Project> :
    Checker<P>,
    HoverProvider<P>,
    CompletionProvider<P>,
    ModelChecker<P>,
    ProofObligationProvider<P>,
    AnimationProvider<P>
