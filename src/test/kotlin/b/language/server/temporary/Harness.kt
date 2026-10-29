package b.language.server.temporary

import b.language.server.analysis.AnalysisDispatcher
import b.language.server.config.Settings
import b.language.server.server.BDocumentService
import b.language.server.server.ServerInterface
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Drives the document service the way a client does. Only this class knows how the service is
 * built, so the snapshots survive refactorings of everything behind it. Closing it ends the ProB
 * processes it started.
 */
class Harness(
    settings: Settings = Settings(),
) : AutoCloseable {
    val communicator = RecordingCommunicator()

    private val server =
        object : ServerInterface {
            override fun getDocumentSettings(uri: String): CompletableFuture<Settings> = CompletableFuture.completedFuture(settings)

            override fun removeDocumentSettings(uri: String) = Unit
        }

    private val analysis = AnalysisDispatcher()
    private val service = BDocumentService(server, communicator, analysis)

    override fun close() {
        service.close()
        analysis.close()
    }

    /** Serialises command results as lsp4j does for the client. */
    private val gson = MessageJsonHandler(emptyMap()).gson

    /** Opens [file] as an editor does, which also checks it. */
    fun open(file: File) = service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(file.toURI().toString(), "b", 1, file.readText())))

    fun hover(
        file: File,
        line: Int,
        column: Int,
    ): String? =
        service
            .hover(HoverParams(TextDocumentIdentifier(file.toURI().toString()), Position(line, column)))
            .get()
            ?.contents
            ?.right
            ?.value

    fun completion(
        file: File,
        line: Int,
        column: Int,
    ) = service
        .completion(CompletionParams(TextDocumentIdentifier(file.toURI().toString()), Position(line, column)))
        .get()
        .right
        .items

    fun command(
        command: String,
        argument: Any,
    ): String = gson.toJson(service.executeCommand(command, listOf(argument)).get())

    fun animate(vararg fields: Pair<String, Any>): String =
        command(
            "prob.animate",
            JsonObject().apply {
                fields.forEach { (key, value) ->
                    when (value) {
                        is Number -> addProperty(key, value)
                        is JsonElement -> add(key, value)
                        else -> addProperty(key, value.toString())
                    }
                }
            },
        )

    fun showPOs(file: File) = command("prob.showPOs", file.toURI().toString())

    fun modelCheck(file: File) = command("prob.modelCheck", file.toURI().toString())

    companion object {
        fun render(diagnostics: Map<String, List<Diagnostic>>): String =
            diagnostics.entries
                .sortedBy { it.key }
                .joinToString("\n") { (file, list) ->
                    "$file\n" +
                        list.joinToString("") { d ->
                            val r = d.range
                            "  ${r.start.line}:${r.start.character}-${r.end.line}:${r.end.character} " +
                                "${d.severity} ${d.source} ${d.message.replace("\n", "\\n")}\n"
                        }
                }
    }
}
