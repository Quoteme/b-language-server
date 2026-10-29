package b.language.server.analysis.prob

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
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
class ProBSession private constructor(
    private var home: ProBHome,
    private var connection: Connection,
) : AutoCloseable {
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

    private val injector get() = connection.injector
    private val animator get() = connection.animator

    /** Switches to the ProB installation at [home], restarting ProB if it changes; the old one is kept if that fails. */
    fun use(home: ProBHome): Either<ProBUnavailable, Unit> =
        either {
            if (home == this@ProBSession.home) return@either
            if (home is ProBHome.Directory) ensure(home.path.isDirectory) { ProBUnavailable.HomeNotFound(home) }
            val previous = System.getProperty(PROB_HOME)
            System.setProperty(PROB_HOME, if (home is ProBHome.Directory) home.path.path else "null")
            val started = connect(home).onLeft { restore(previous) }.bind()
            connection.animator.kill()
            connection = started
            this@ProBSession.home = home
        }

    private fun restore(previous: String?) {
        if (previous == null) System.clearProperty(PROB_HOME) else System.setProperty(PROB_HOME, previous)
    }

    /** An instance of a prob-java class, such as a model factory. */
    fun <T> instance(type: Class<T>): T = injector.getInstance(type)

    /** A new state space; the caller must kill it. */
    fun createStateSpace(preferences: ProBPreferences = ProBPreferences()): StateSpace =
        animator.createStateSpace().also { it.changePreferences(preferences.toProB()) }

    /**
     * Runs [block] on a new state space and kills it afterwards, even if [block] fails unexpectedly,
     * so that no ProB state is left behind.
     */
    fun <T> withStateSpace(
        preferences: ProBPreferences,
        block: (StateSpace) -> T,
    ): T = Releasing(createStateSpace(preferences)) { it.kill() }.use { block(it.value) }

    /** Kills the state space of the current animation, if there is one. */
    fun unloadCurrent() {
        val selector = injector.getInstance(AnimationSelector::class.java)
        val trace = selector.currentTrace ?: return
        selector.changeCurrentAnimation(null)
        trace.stateSpace.kill()
    }

    /** Runs [block] and collects the warnings ProB reports meanwhile; the listener is removed in any case. */
    fun <T> collectingWarnings(block: () -> T): WithWarnings<T> {
        val collector = WarningCollector().also(animator::addWarningListener)
        return Releasing(collector, animator::removeWarningListener).use { WithWarnings(block(), it.value.collected.toList()) }
    }

    /** A [value] that [release] cleans up when it is closed, so that `use` cleans it up in any case. */
    private class Releasing<T>(
        val value: T,
        private val release: (T) -> Unit,
    ) : AutoCloseable {
        override fun close() = release(value)
    }

    /** Ends the ProB process. */
    override fun close() = connection.animator.kill()

    companion object {
        private const val PROB_HOME = "prob.home"

        /** Starts ProB from prob-java's own installation. */
        fun start(): Either<ProBUnavailable, ProBSession> = connect(ProBHome.Default).map { ProBSession(ProBHome.Default, it) }

        private fun connect(home: ProBHome): Either<ProBUnavailable, Connection> =
            Either
                .catchOrThrow<CliError, Connection> { Connection(ProBModule.injector()) }
                .mapLeft { ProBUnavailable.StartFailed(home, it.message) }
    }
}
