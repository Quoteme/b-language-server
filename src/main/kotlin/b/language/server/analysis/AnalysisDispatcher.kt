package b.language.server.analysis

import arrow.core.Either
import b.language.server.analysis.classicB.ClassicBAnalysis
import b.language.server.analysis.eventB.EventBAnalysis
import b.language.server.analysis.prob.ProBSession
import b.language.server.analysis.prob.animation.AnimationModel
import b.language.server.config.Settings
import b.language.server.project.Project
import b.language.server.project.SourcePosition

/**
 * Analyses any project with the analysis of its kind. Classical B and Event-B share one ProB
 * process, which closing the dispatcher ends.
 */
class AnalysisDispatcher(
    private val session: ProBSession,
) : Analysis<Project>,
    AutoCloseable {
    private val classicB = ClassicBAnalysis(session)
    private val eventB = EventBAnalysis(session)

    override fun check(
        project: Project,
        settings: Settings,
    ): Either<AnalysisFailure, List<Problem>> = project.match({ classicB.check(it, settings) }, { eventB.check(it, settings) })

    override fun hover(
        project: Project,
        at: SourcePosition,
    ): Markdown? = project.match({ classicB.hover(it, at) }, { eventB.hover(it, at) })

    override fun complete(
        project: Project,
        at: SourcePosition,
    ): List<CompletionCandidate> = project.match({ classicB.complete(it, at) }, { eventB.complete(it, at) })

    override fun modelCheck(
        project: Project,
        settings: Settings,
    ): ModelCheckReport = project.match({ classicB.modelCheck(it, settings) }, { eventB.modelCheck(it, settings) })

    override fun proofObligations(project: Project): List<ProofObligation> =
        project.match(classicB::proofObligations, eventB::proofObligations)

    override fun animationModel(project: Project): AnimationModel = project.match(classicB::animationModel, eventB::animationModel)

    override fun close() = session.close()
}
