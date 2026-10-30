package b.language.server.server

import arrow.core.Either
import b.language.server.analysis.Analysis
import b.language.server.analysis.prob.animation.Animation
import b.language.server.analysis.prob.animation.AnimationAction
import b.language.server.analysis.prob.animation.AnimationRequest
import b.language.server.analysis.prob.animation.AnimationResponse
import b.language.server.analysis.prob.animation.Failure
import b.language.server.analysis.prob.animation.VisualizationRequest
import b.language.server.project.OpenDocuments
import b.language.server.project.Project
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CompletableFuture

/** Runs the [Command]s the client sends, and owns the animation they drive. */
class Commands(
    private val checks: DocumentChecks,
    private val documents: OpenDocuments,
    private val analysis: Analysis<Project>,
) : AutoCloseable {
    private val animation = Animation()

    /** Runs a [Command] on the document or with the request given as its first argument. */
    fun execute(
        command: String,
        arguments: List<Any?>,
    ): CompletableFuture<Any?> {
        val argument = arguments.firstOrNull()
        return when (Command.of(command)) {
            Command.ANIMATE -> {
                val request = decode<AnimationRequest>(argument) ?: return CompletableFuture.completedFuture(null)
                CompletableFuture.supplyAsync {
                    request.fold({ Failure(it.message) }, { synchronized(animation) { animate(it) } })
                }
            }

            Command.VISUALIZE -> {
                val request = decode<VisualizationRequest>(argument) ?: return CompletableFuture.completedFuture(null)
                CompletableFuture.supplyAsync {
                    request.fold({ Failure(it.message) }, { synchronized(animation) { animation.visualize(it) } })
                }
            }

            Command.MODEL_CHECK -> {
                val file = fileArgument(argument) ?: return CompletableFuture.completedFuture(null)
                checks.modelCheck(file).thenApply<Any?> { null }
            }

            Command.DISCHARGE_POS -> {
                val file = fileArgument(argument) ?: return CompletableFuture.completedFuture(null)
                checks.check(file) { it.copy(poTimeout = it.poTimeout * 10) }
                CompletableFuture.completedFuture(null)
            }

            Command.SHOW_POS -> {
                val file = fileArgument(argument) ?: return CompletableFuture.completedFuture(null)
                val obligations = synchronized(analysis) { analysis.proofObligations(documents.projectOf(file)) }
                CompletableFuture.completedFuture(obligations.map { ProofObligationView.of(it) })
            }

            Command.OPEN_ANIMATION, null -> {
                CompletableFuture.completedFuture(null)
            }
        }
    }

    private fun animate(request: AnimationRequest): AnimationResponse =
        when (request.action) {
            AnimationAction.START -> {
                val uri = request.uri ?: return Failure("No file to animate")
                animation.start(analysis.animationModel(documents.projectOf(fileOf(uri))))
            }

            AnimationAction.STOP -> {
                animation.stop()
            }

            else -> {
                animation.perform(request.action, request.argument, request.count)
            }
        }

    /** The document a command is about, given by its URI. */
    private fun fileArgument(argument: Any?): File? {
        val uri = (argument as? JsonPrimitive)?.asString ?: argument?.toString()?.trim('"') ?: return null
        return fileOf(uri)
    }

    /** Ends the animation's ProB process; the analysis belongs to whoever created it. */
    override fun close() = synchronized(animation) { animation.close() }

    private companion object {
        val requests =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }

        /**
         * Reads a request the client sent as a JSON object, or null if it sent none; missing or
         * unknown fields take their defaults.
         */
        inline fun <reified T> decode(argument: Any?): Either<InvalidRequest, T>? =
            (argument as? JsonObject)?.let { json ->
                Either
                    .catchOrThrow<SerializationException, T> { requests.decodeFromString<T>(json.toString()) }
                    .mapLeft { InvalidRequest("Invalid request: ${it.message}") }
            }
    }
}

/** A request the client sent that does not have the expected shape. */
data class InvalidRequest(
    val message: String,
)
