package b.language.server.analysis

import b.language.server.analysis.classicB.ClassicBAnalysis
import b.language.server.analysis.eventB.EventBAnalysis
import b.language.server.analysis.prob.ProBSession
import b.language.server.analysis.prob.animation.AnimationModel
import b.language.server.config.Settings
import b.language.server.project.Project
import b.language.server.project.SourcePosition
import b.language.server.project.classicB.ClassicBProject
import b.language.server.project.eventB.EventBProject

/**
 * Analyses any project with the analysis of its kind. Classical B and Event-B share one ProB
 * process, which closing the dispatcher ends.
 */
class AnalysisDispatcher(
    private val session: ProBSession = ProBSession(),
) : Analysis<Project>,
    AutoCloseable {
    private val classicB = ClassicBAnalysis(session)
    private val eventB = EventBAnalysis(session)

    override fun check(
        project: Project,
        settings: Settings,
    ): List<Problem> =
        when (project) {
            is ClassicBProject -> classicB.check(project, settings)
            is EventBProject -> eventB.check(project, settings)
            else -> throw UnsupportedProject(project)
        }

    override fun hover(
        project: Project,
        at: SourcePosition,
    ): Markdown? =
        when (project) {
            is ClassicBProject -> classicB.hover(project, at)
            is EventBProject -> eventB.hover(project, at)
            else -> throw UnsupportedProject(project)
        }

    override fun complete(
        project: Project,
        at: SourcePosition,
    ): List<CompletionCandidate> =
        when (project) {
            is ClassicBProject -> classicB.complete(project, at)
            is EventBProject -> eventB.complete(project, at)
            else -> throw UnsupportedProject(project)
        }

    override fun modelCheck(
        project: Project,
        settings: Settings,
    ): ModelCheckReport =
        when (project) {
            is ClassicBProject -> classicB.modelCheck(project, settings)
            is EventBProject -> eventB.modelCheck(project, settings)
            else -> throw UnsupportedProject(project)
        }

    override fun proofObligations(project: Project): List<ProofObligation> =
        when (project) {
            is ClassicBProject -> classicB.proofObligations(project)
            is EventBProject -> eventB.proofObligations(project)
            else -> throw UnsupportedProject(project)
        }

    override fun animationModel(project: Project): AnimationModel =
        when (project) {
            is ClassicBProject -> classicB.animationModel(project)
            is EventBProject -> eventB.animationModel(project)
            else -> throw UnsupportedProject(project)
        }

    override fun close() = session.close()
}

/** A kind of project that no analysis is registered for in [AnalysisDispatcher]. */
class UnsupportedProject(
    project: Project,
) : IllegalArgumentException("no analysis for ${project::class.simpleName} ${project.mainFile}")
