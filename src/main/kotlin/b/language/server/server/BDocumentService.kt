package b.language.server.server

import b.language.server.analysis.Analysis
import b.language.server.analysis.Problem
import b.language.server.analysis.prob.ProBHomeNotFound
import b.language.server.analysis.prob.animation.Animation
import b.language.server.analysis.prob.animation.AnimationAction
import b.language.server.analysis.prob.animation.AnimationRequest
import b.language.server.analysis.prob.animation.AnimationResponse
import b.language.server.analysis.prob.animation.Failure
import b.language.server.analysis.prob.animation.VisualizationRequest
import b.language.server.client.CommunicatorInterface
import b.language.server.config.Settings
import b.language.server.project.OpenDocuments
import b.language.server.project.Project
import b.language.server.project.SourcePosition
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.serialization.json.Json
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.lsp4j.Command as LspCommand

/** Translates the client's requests about documents to the analysis, and its results back. */
class BDocumentService(
    private val server: ServerInterface,
    private val communicator: CommunicatorInterface,
    private val analysis: Analysis<Project>,
) : TextDocumentService,
    AutoCloseable {
    private val documents = OpenDocuments()

    /** For each checked file, the problems last published, which may lie in the files it includes. */
    private val published = ConcurrentHashMap<File, List<Problem>>()
    private val animation = Animation()

    private fun fileOf(uri: String) = File(URI(uri).path)

    private fun projectOf(file: File) = Project.of(file, documents.textOf(file).orEmpty())

    /**
     * The document open notification is sent from the client to the server to signal newly opened
     * text documents. The document's truth is now managed by the client.
     */
    override fun didOpen(params: DidOpenTextDocumentParams) {
        val file = fileOf(params.textDocument.uri)
        documents.update(file, params.textDocument.text)
        checkDocument(file)
    }

    /** The document save notification is sent from the client to the server when the document was saved. */
    override fun didSave(params: DidSaveTextDocumentParams) {
        val file = fileOf(params.textDocument.uri)
        communicator.sendDebugMessage("document $file was saved", MessageType.Info)
        checkDocument(file)
    }

    /**
     * Checks a document under the client's settings for it.
     * @param adjust changes the client's settings for this check
     */
    fun checkDocument(
        file: File,
        adjust: (Settings) -> Settings = { it },
    ) {
        server.getDocumentSettings(file.absolutePath).thenAccept { original ->
            val settings = adjust(original)
            communicator.setDebugMode(settings.debugMode)
            communicator.sendDebugMessage("settings are $settings", MessageType.Info)
            try {
                val problems = synchronized(analysis) { analysis.check(projectOf(file), settings) }
                publish(file, problems)
                communicator.showMessage("Evaluation done: ${problems.size} problem(s)", MessageType.Log)
            } catch (e: ProBHomeNotFound) {
                communicator.sendDebugMessage(e.localizedMessage, MessageType.Info)
                communicator.showMessage(e.localizedMessage, MessageType.Error)
            }
        }
    }

    /** Publishes the problems found by checking [file], and clears the files that no longer have any. */
    private fun publish(
        file: File,
        problems: List<Problem>,
    ) {
        val byTarget = problems.groupBy { it.location.file.toURI().path }
        byTarget.forEach { (target, own) -> communicator.publishDiagnostics(target, own.map { it.toDiagnostic(SOURCE) }) }
        val stale = published[file].orEmpty().map { it.location.file.toURI().path }.toSet() - byTarget.keys
        stale.forEach { communicator.publishDiagnostics(it, emptyList()) }
        communicator.sendDebugMessage("invalidating old files $stale", MessageType.Info)
        published[file] = problems
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> =
        CompletableFuture.supplyAsync {
            val file = fileOf(params.textDocument.uri)
            val text = documents.textOf(file) ?: return@supplyAsync null
            val markdown =
                try {
                    analysis.hover(Project.of(file, text), SourcePosition(params.position.line, params.position.character))
                } catch (e: RuntimeException) {
                    communicator.sendDebugMessage("hover failed: ${e.message}", MessageType.Info)
                    null
                } ?: return@supplyAsync null
            Hover(MarkupContent(MarkupKind.MARKDOWN, markdown.text))
        }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> =
        CompletableFuture.supplyAsync {
            val file = fileOf(params.textDocument.uri)
            val items =
                try {
                    analysis.complete(projectOf(file), SourcePosition(params.position.line, params.position.character)).map { it.toLsp() }
                } catch (e: RuntimeException) {
                    communicator.sendDebugMessage("completion failed: ${e.message}", MessageType.Info)
                    emptyList()
                }
            Either.forRight(CompletionList(false, items))
        }

    /**
     * Offers the ProB commands as source actions of the whole file, and only when asked for explicitly,
     * so clients show no lightbulb on every line.
     */
    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<LspCommand, CodeAction>>> {
        val uri = params.textDocument.uri
        val wanted =
            params.context.triggerKind != CodeActionTriggerKind.Automatic &&
                params.context.only?.any { CodeActionKind.Source.startsWith(it) } ?: true
        val actions =
            Command.entries.filter { wanted && it.title != null }.map { command ->
                Either.forRight<LspCommand, CodeAction>(
                    CodeAction(command.title).apply {
                        kind = CodeActionKind.Source
                        this.command = LspCommand(command.title, command.id, listOf(uri))
                    },
                )
            }
        return CompletableFuture.completedFuture(actions)
    }

    /** Runs a [Command] on the document or with the request given as its first argument. */
    fun executeCommand(
        command: String,
        arguments: List<Any?>,
    ): CompletableFuture<Any?> {
        val argument = arguments.firstOrNull()
        return when (Command.of(command)) {
            Command.ANIMATE -> {
                val request = decode<AnimationRequest>(argument) ?: return CompletableFuture.completedFuture(null)
                CompletableFuture.supplyAsync { synchronized(animation) { animate(request) } }
            }

            Command.VISUALIZE -> {
                val request = decode<VisualizationRequest>(argument) ?: return CompletableFuture.completedFuture(null)
                CompletableFuture.supplyAsync { synchronized(animation) { animation.visualize(request) } }
            }

            Command.MODEL_CHECK -> {
                val file = fileArgument(argument) ?: return CompletableFuture.completedFuture(null)
                server.getDocumentSettings(file.absolutePath).thenApply { settings ->
                    val report = synchronized(analysis) { analysis.modelCheck(projectOf(file), settings) }
                    publish(file, published[file].orEmpty() + report.problems)
                    communicator.showMessage(report.summary, MessageType.Info)
                    communicator.showMessage("Evaluation done: ${report.problems.size} problem(s) found by model checking", MessageType.Log)
                    null
                }
            }

            Command.DISCHARGE_POS -> {
                val file = fileArgument(argument) ?: return CompletableFuture.completedFuture(null)
                checkDocument(file) { it.copy(poTimeout = it.poTimeout * 10) }
                CompletableFuture.completedFuture(null)
            }

            Command.SHOW_POS -> {
                val file = fileArgument(argument) ?: return CompletableFuture.completedFuture(null)
                val obligations = synchronized(analysis) { analysis.proofObligations(projectOf(file)) }
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
                animation.start(analysis.animationModel(projectOf(fileOf(uri))))
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

    /** Forgets the settings of a closed document; its text is kept, as the files that refer to it may still be open. */
    override fun didClose(params: DidCloseTextDocumentParams) {
        val path = URI(params.textDocument.uri).path
        communicator.sendDebugMessage("document $path was closed - removing meta data", MessageType.Info)
        server.removeDocumentSettings(path)
    }

    /** Keeps the editor's text for hovers and completions; checking happens on save, since ProB reads the files on disk. */
    override fun didChange(params: DidChangeTextDocumentParams) {
        params.contentChanges.lastOrNull()?.let { documents.update(fileOf(params.textDocument.uri), it.text) }
    }

    /** Ends the animation's ProB process; the analysis belongs to whoever created it. */
    override fun close() = synchronized(animation) { animation.close() }

    private companion object {
        /** The source the client shows for the problems. */
        const val SOURCE = "ProB"

        val requests =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }

        /** Reads a request the client sent as a JSON object; missing or unknown fields take their defaults. */
        inline fun <reified T> decode(argument: Any?): T? = (argument as? JsonObject)?.let { requests.decodeFromString<T>(it.toString()) }
    }
}
