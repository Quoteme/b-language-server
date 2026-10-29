package b.language.server

import b.language.server.analysis.legacy.Animation
import b.language.server.analysis.legacy.Completion
import b.language.server.analysis.legacy.CouldNotFindProBHomeException
import b.language.server.analysis.legacy.ProBInterface
import b.language.server.client.CommunicatorInterface
import b.language.server.config.Settings
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.TextDocumentChangeRegistrationOptions
import org.eclipse.lsp4j.TextDocumentRegistrationOptions
import org.eclipse.lsp4j.TextDocumentSaveRegistrationOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import b.language.server.analysis.legacy.Hover as HoverText

class BDocumentService(
    private val server: ServerInterface,
    private val communicator: CommunicatorInterface,
    private val proBInterface: ProBInterface,
) : TextDocumentService {
    private val documents = ConcurrentHashMap<String, String>()
    private val issueTracker: ConcurrentHashMap<String, Set<String>> = ConcurrentHashMap()
    private val published: ConcurrentHashMap<String, Map<String, List<Diagnostic>>> = ConcurrentHashMap()
    private val animation by lazy { Animation(communicator) }

    companion object {
        const val MODEL_CHECK = "prob.modelCheck"
        const val DISCHARGE_POS = "prob.dischargePOs"
        const val SHOW_POS = "prob.showPOs"
        const val ANIMATE = "prob.animate"

        /** Handled by the client, which opens its animation panel and then sends [ANIMATE]. */
        const val OPEN_ANIMATION = "prob.animate.open"

        /** Shows a visualisation of the animation's current state, as ProB Tcl/Tk does. */
        const val VISUALIZE = "prob.visualize"
        val COMMANDS = listOf(MODEL_CHECK, DISCHARGE_POS, SHOW_POS, ANIMATE, VISUALIZE)
    }

    /**
     * The document open notification is sent from the client to the server to
     * signal newly opened text documents. The document's truth is now managed
     * by the client and the server must not try to read the document's truth
     * using the document's uri.
     *
     * Registration Options: TextDocumentRegistrationOptions
     */
    override fun didOpen(params: DidOpenTextDocumentParams?) {
        documents[params?.textDocument!!.uri] = params.textDocument!!.text
        checkDocument(File(URI(params.textDocument.uri).path))
    }

    /**
     * The document save notification is sent from the client to the server when
     * the document for saved in the client.
     *
     * Registration Options: TextDocumentSaveRegistrationOptions
     */
    override fun didSave(params: DidSaveTextDocumentParams?) {
        communicator.sendDebugMessage("document ${File(URI(params!!.textDocument.uri).path)} was saved", MessageType.Info)
        checkDocument(File(URI(params.textDocument.uri).path))
    }

    /**
     * checks a document via prob and the set options
     * @param file the uri to perform actions on
     * @param adjust changes the client's settings for this check
     */
    fun checkDocument(
        file: File,
        adjust: (Settings) -> Settings = { it },
    ) {
        val clientSettings = server.getDocumentSettings(file.absolutePath)
        communicator.sendDebugMessage("waiting for document settings", MessageType.Info)

        clientSettings.thenAccept { original ->
            val settings = adjust(original)
            communicator.setDebugMode(settings.debugMode)
            communicator.sendDebugMessage("settings are $settings", MessageType.Info)

            try {
                val diagnostics: List<Diagnostic> = synchronized(proBInterface) { proBInterface.checkDocument(file, settings) }
                publish(file, diagnostics.groupBy { it.source })
                communicator.showMessage("Evaluation done: ${diagnostics.size} problem(s)", MessageType.Log)
            } catch (e: CouldNotFindProBHomeException) {
                communicator.sendDebugMessage(e.localizedMessage, MessageType.Info)
                communicator.showMessage(e.localizedMessage, MessageType.Error)
            }
        }
    }

    /** Publishes the problems found for [file], clearing files that no longer have any. */
    private fun publish(
        file: File,
        byFile: Map<String, List<Diagnostic>>,
    ) {
        byFile.values.flatten().forEach { it.source = "ProB" }
        byFile.forEach { (target, diagnostics) -> communicator.publishDiagnostics(target, diagnostics) }
        val invalidFiles = calculateToInvalidate(file, byFile.keys.toList())
        invalidFiles.forEach { uri -> communicator.publishDiagnostics(uri, listOf()) }
        communicator.sendDebugMessage("invalidating old files $invalidFiles", MessageType.Info)
        issueTracker[file.absolutePath] = byFile.keys
        published[file.absolutePath] = byFile
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> =
        CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val file = File(URI(uri).path)
            val text = documents[uri] ?: file.takeIf { it.isFile }?.readText() ?: return@supplyAsync null
            val markdown =
                try {
                    HoverText.at(
                        file,
                        text,
                        params.position.line,
                        params.position.character,
                        proBInterface.proofObligations(file),
                        proBInterface.identifiers(file),
                    )
                } catch (e: RuntimeException) {
                    communicator.sendDebugMessage("hover failed: ${e.message}", MessageType.Info)
                    null
                } ?: return@supplyAsync null
            Hover(MarkupContent(MarkupKind.MARKDOWN, markdown))
        }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> =
        CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val file = File(URI(uri).path)
            val items =
                try {
                    Completion.at(
                        file,
                        documents[uri].orEmpty(),
                        params.position.line,
                        params.position.character,
                        proBInterface.proofObligations(file),
                        proBInterface.identifiers(file),
                    )
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
    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        val uri = params.textDocument.uri
        val wanted =
            params.context.triggerKind != CodeActionTriggerKind.Automatic &&
                params.context.only?.any { CodeActionKind.Source.startsWith(it) } ?: true
        val actions =
            listOf(
                "ProB: model check for deadlocks and invariant violations" to MODEL_CHECK,
                "ProB: discharge proof obligations with a longer timeout" to DISCHARGE_POS,
                "ProB: list proof obligations" to SHOW_POS,
                "ProB: animate" to OPEN_ANIMATION,
            ).takeIf { wanted }.orEmpty().map { (title, command) ->
                Either.forRight<Command, CodeAction>(
                    CodeAction(title).apply {
                        kind = CodeActionKind.Source
                        this.command = Command(title, command, listOf(uri))
                    },
                )
            }
        return CompletableFuture.completedFuture(actions)
    }

    /** Runs one of [COMMANDS] on the document given as its first argument. */
    fun executeCommand(
        command: String,
        arguments: List<Any?>,
    ): CompletableFuture<Any?> {
        val argument = arguments.firstOrNull()
        if (command == ANIMATE) {
            val request = argument as? JsonObject ?: return CompletableFuture.completedFuture(null)

            fun field(name: String) = request.get(name)?.takeUnless { it.isJsonNull }
            val action = field("action")?.asString ?: "view"
            return CompletableFuture.supplyAsync {
                synchronized(animation) {
                    when (action) {
                        "start" -> animation.start(File(URI(field("uri")!!.asString).path))
                        "stop" -> animation.stop().let { mapOf("stopped" to true) }
                        else -> animation.perform(action, field("argument")?.asString, field("count")?.asInt ?: 1)
                    }
                }
            }
        }
        if (command == VISUALIZE) {
            val request = argument as? JsonObject ?: return CompletableFuture.completedFuture(null)

            fun field(name: String) = request.get(name)?.takeUnless { it.isJsonNull }
            val formulas = field("formulas")?.asJsonArray?.map { it.asString }.orEmpty()
            return CompletableFuture.supplyAsync {
                synchronized(animation) {
                    animation.visualize(
                        field("kind")?.asString ?: "dot",
                        field("command")?.asString ?: "",
                        formulas,
                        field("format")?.asString ?: "svg",
                    )
                }
            }
        }
        val uri =
            (argument as? JsonPrimitive)?.asString ?: argument?.toString()?.trim('"')
                ?: return CompletableFuture.completedFuture(null)
        val file = File(URI(uri).path)
        return when (command) {
            MODEL_CHECK -> {
                server.getDocumentSettings(file.absolutePath).thenApply { settings ->
                    val (found, summary) = synchronized(proBInterface) { proBInterface.modelCheck(file, settings) }
                    val merged = published[file.absolutePath].orEmpty().mapValues { it.value.toMutableList() }.toMutableMap()
                    found.forEach { merged.getOrPut(it.source) { mutableListOf() } += it }
                    publish(file, merged)
                    communicator.showMessage(summary, MessageType.Info)
                    communicator.showMessage("Evaluation done: ${found.size} problem(s) found by model checking", MessageType.Log)
                    null
                }
            }

            DISCHARGE_POS -> {
                checkDocument(file) { it.copy(poTimeout = it.poTimeout * 10) }
                CompletableFuture.completedFuture(null)
            }

            SHOW_POS -> {
                CompletableFuture.completedFuture(
                    synchronized(proBInterface) { proBInterface.proofObligations(file) }.map { po ->
                        mapOf(
                            "uri" to po.file.toURI().toString(),
                            "line" to po.line - 1,
                            "character" to po.pos - 1,
                            "name" to po.name,
                            "status" to po.status.name.lowercase(),
                            "detail" to po.detail,
                        )
                    },
                )
            }

            else -> {
                CompletableFuture.completedFuture(null)
            }
        }
    }

    /**
     * Gets all uris that are no longer contain problems
     * @param file the uri of the current main file
     * @param filesWithProblems uris of files containing problems
     */
    private fun calculateToInvalidate(
        file: File,
        filesWithProblems: List<String>,
    ): List<String> {
        val currentlyDisplayed = issueTracker[file.absolutePath].orEmpty()
        return currentlyDisplayed.subtract(filesWithProblems).toList()
    }

    /**
     * The document close notification is sent from the client to the server
     * when the document got closed in the client. The document's truth now
     * exists where the document's uri points to (e.g. if the document's uri is
     * a file uri the truth now exists on disk).
     *
     * Registration Options: TextDocumentRegistrationOptions
     */
    override fun didClose(params: DidCloseTextDocumentParams?) {
        communicator.sendDebugMessage("document ${URI(params!!.textDocument.uri).path} was closed - removing meta data", MessageType.Info)
        server.removeDocumentSettings((URI(params.textDocument.uri).path))
    }

    /**
     * The document change notification is sent from the client to the server to
     * signal changes to a text document.
     *
     * Registration Options: TextDocumentChangeRegistrationOptions
     */
    override fun didChange(params: DidChangeTextDocumentParams?) {
        // ProB checks the file on disk, so changes are only kept for hovering; saving checks.
        params!!.contentChanges.lastOrNull()?.let { documents[params.textDocument.uri] = it.text }
    }
}
