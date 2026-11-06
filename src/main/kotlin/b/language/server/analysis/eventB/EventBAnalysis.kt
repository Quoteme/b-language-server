package b.language.server.analysis.eventB

import b.language.server.analysis.Analysis
import b.language.server.analysis.Checker
import b.language.server.analysis.CompletionProvider
import b.language.server.analysis.HoverProvider
import b.language.server.analysis.LastCheck
import b.language.server.analysis.ModelChecker
import b.language.server.analysis.ProofObligation
import b.language.server.analysis.prob.ProBSession
import b.language.server.analysis.prob.animation.AnimationModel
import b.language.server.project.eventB.EventBProject

/**
 * The analysis of Event-B in Camille notation. It follows Rodin's steps, on Camille text instead of Rodin's files:
 *
 * ```
 * Rodin: .bum/.buc → static checker → .bcm/.bcc
 * Here:  Camille text → StaticChecker → CheckedComponent
 * ```
 *
 * Sources: here [StaticChecker]; Rodin [file types](https://wiki.event-b.org/index.php/Rodin_File_Types).
 *
 * Model checking and animation load the model through [ProBTranslation].
 */
class EventBAnalysis private constructor(
    session: ProBSession,
    private val obligations: LastCheck<List<ProofObligation>>,
) : Analysis<EventBProject>,
    Checker<EventBProject> by EventBChecker(session, obligations),
    HoverProvider<EventBProject> by EventBHover(obligations),
    CompletionProvider<EventBProject> by EventBCompletion(obligations),
    ModelChecker<EventBProject> by EventBModelChecker(session) {
    constructor(session: ProBSession) : this(session, LastCheck(emptyList()))

    override fun proofObligations(project: EventBProject): List<ProofObligation> = obligations[project.mainFile]

    override fun animationModel(project: EventBProject): AnimationModel = EventBAnimationModel(project)
}
