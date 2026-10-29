package b.language.server.analysis.prob

import com.google.inject.Injector
import de.prob.animator.ReusableAnimator
import de.prob.animator.domainobjects.ErrorItem
import de.prob.exception.CliError
import de.prob.statespace.AnimationSelector
import de.prob.statespace.StateSpace

/**
 * The ProB process that checks models. Only one model is loaded at a time, so callers must not use
 * a session concurrently. Closing it ends the process.
 */
class ProBSession : AutoCloseable {
    /** A result, and the warnings ProB reported while computing it. */
    data class WithWarnings<T>(
        val result: T,
        val warnings: List<ErrorItem>,
    )

    /**
     * A running ProB. The animator is fetched once: it is no singleton, so every instance Guice
     * creates starts another ProB process.
     */
    private class Connection(
        val injector: Injector,
    ) {
        val animator: ReusableAnimator = injector.getInstance(ReusableAnimator::class.java)
    }

    private var home: ProBHome = ProBHome.Default
    private var connection: Connection = start()

    private val injector get() = connection.injector
    private val animator get() = connection.animator

    private fun start(): Connection =
        try {
            Connection(ProBModule.injector())
        } catch (e: CliError) {
            throw IllegalStateException("wrong path to prob $home", e)
        }

    /** Switches to the ProB installation at [home], restarting ProB if it changes. */
    fun use(home: ProBHome) {
        if (home == this.home) return
        when (home) {
            ProBHome.Default -> {
                System.setProperty("prob.home", "null")
            }

            is ProBHome.Directory -> {
                if (!home.path.isDirectory) throw ProBHomeNotFound(home)
                System.setProperty("prob.home", home.path.path)
            }
        }
        this.home = home
        connection.animator.kill()
        connection = start()
    }

    /** An instance of a prob-java class, such as a model factory. */
    fun <T> instance(type: Class<T>): T = injector.getInstance(type)

    fun createStateSpace(preferences: ProBPreferences = ProBPreferences()): StateSpace =
        animator.createStateSpace().also { it.changePreferences(preferences.toProB()) }

    /** Kills the state space of the current animation, if there is one. */
    fun unloadCurrent() {
        val selector = injector.getInstance(AnimationSelector::class.java)
        val trace = selector.currentTrace ?: return
        selector.changeCurrentAnimation(null)
        trace.stateSpace.kill()
    }

    /** Runs [block] and collects the warnings ProB reports meanwhile. */
    fun <T> collectingWarnings(block: () -> T): WithWarnings<T> {
        val collector = WarningCollector()
        animator.addWarningListener(collector)
        try {
            return WithWarnings(block(), collector.collected.toList())
        } finally {
            animator.removeWarningListener(collector)
        }
    }

    /** Ends the ProB process. */
    override fun close() = connection.animator.kill()
}
