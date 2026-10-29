package b.language.server.analysis.prob

import arrow.core.Either
import de.prob.animator.domainobjects.ErrorItem
import de.prob.exception.ProBError
import java.io.File

/** ProB rejected a command or a model: the errors it reported, and its own description. */
data class ProBFailure(
    val errors: List<ErrorItem>,
    val description: String?,
) {
    /** The first error ProB reported, or its description, or else [fallback]. */
    fun message(fallback: String): String = errors.firstOrNull()?.message ?: description ?: fallback

    companion object {
        /** Calls into prob-java, which reports failures by throwing [ProBError]. */
        inline fun <T> catching(call: () -> T): Either<ProBFailure, T> =
            Either.catchOrThrow<ProBError, T>(call).mapLeft { ProBFailure(it.errors, it.message) }
    }
}

/** Why a model could not be loaded into ProB. */
sealed interface LoadFailure {
    /** ProB found errors in the model. */
    data class Rejected(
        val failure: ProBFailure,
    ) : LoadFailure

    /** A file of the model could not be read. */
    data class Unreadable(
        val file: File,
        val reason: String?,
    ) : LoadFailure
}

/** Why loading failed, for the user. */
val LoadFailure.explanation: String
    get() =
        when (this) {
            is LoadFailure.Rejected -> failure.message("ProB could not load the model")
            is LoadFailure.Unreadable -> "Could not read ${file.name}: $reason"
        }
