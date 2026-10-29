package b.language.server.analysis.prob

import de.prob.check.ConsistencyChecker
import de.prob.check.ModelCheckErrorUncovered
import de.prob.check.ModelCheckingOptions
import de.prob.statespace.StateSpace
import de.prob.statespace.Trace
import java.time.Duration

/** What ProB's model checker found. */
sealed interface ModelCheckOutcome {
    val message: String

    data class NoError(
        override val message: String,
    ) : ModelCheckOutcome

    /** An error, and the trace that leads to it. */
    data class ErrorFound(
        override val message: String,
        val kind: Kind,
        val trace: Trace,
    ) : ModelCheckOutcome {
        enum class Kind { INVARIANT_VIOLATION, DEADLOCK, OTHER }

        /** The events that lead to the error, as the user knows them. */
        val path: List<String> =
            trace.transitionList
                .map { it.name }
                .filter { it != Transitions.SETUP_CONSTANTS }
                .map { if (it == Transitions.INITIALISE_MACHINE) Transitions.INITIALISATION else it }

        /** " after e1, e2" or nothing, to complete a message about the error. */
        val after: String get() = if (path.isEmpty()) "" else " after ${path.joinToString(", ")}"
    }

    companion object {
        private val TIME_LIMIT = Duration.ofSeconds(60)

        /** Model checks the model loaded in [stateSpace] for deadlocks and invariant violations. */
        fun of(
            stateSpace: StateSpace,
            stateLimit: Int,
        ): ModelCheckOutcome {
            val options =
                ModelCheckingOptions()
                    .checkDeadlocks(true)
                    .checkInvariantViolations(true)
                    .stateLimit(stateLimit)
                    .timeLimit(TIME_LIMIT)
            val result = ConsistencyChecker(stateSpace, options).call()
            if (result !is ModelCheckErrorUncovered) return NoError(result.message)
            val kind =
                when {
                    result.message.contains("invariant", ignoreCase = true) -> ErrorFound.Kind.INVARIANT_VIOLATION
                    result.message.contains("deadlock", ignoreCase = true) -> ErrorFound.Kind.DEADLOCK
                    else -> ErrorFound.Kind.OTHER
                }
            return ErrorFound(result.message, kind, result.getTrace(stateSpace))
        }
    }
}
