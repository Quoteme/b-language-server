package b.language.server.analysis.classicB

import b.language.server.analysis.Analysis
import b.language.server.analysis.Checker
import b.language.server.analysis.CompletionProvider
import b.language.server.analysis.HoverProvider
import b.language.server.analysis.LastCheck
import b.language.server.analysis.ModelChecker
import b.language.server.analysis.ProofObligation
import b.language.server.analysis.prob.ProBSession
import b.language.server.analysis.prob.animation.AnimationModel
import b.language.server.project.classicB.ClassicBProject

/** The analysis of classical B, which ProB does. */
class ClassicBAnalysis private constructor(
    session: ProBSession,
    identifiers: LastCheck<IdentifierCatalog>,
) : Analysis<ClassicBProject>,
    Checker<ClassicBProject> by ClassicBChecker(session, identifiers),
    HoverProvider<ClassicBProject> by ClassicBHover(identifiers),
    CompletionProvider<ClassicBProject> by ClassicBCompletion(identifiers),
    ModelChecker<ClassicBProject> by ClassicBModelChecker(session) {
    constructor(session: ProBSession) : this(session, LastCheck(IdentifierCatalog.EMPTY))

    /** Classical B has proof obligations too, but ProB does not generate them. */
    override fun proofObligations(project: ClassicBProject): List<ProofObligation> = emptyList()

    override fun animationModel(project: ClassicBProject): AnimationModel = ClassicBAnimationModel(project)
}
