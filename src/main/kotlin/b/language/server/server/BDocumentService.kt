package b.language.server.server

import arrow.core.Either
import arrow.core.getOrElse
import b.language.server.analysis.Analysis
import b.language.server.client.CommunicatorInterface
import b.language.server.project.OpenDocuments
import b.language.server.project.Project
import b.language.server.project.SourcePosition
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
import org.eclipse.lsp4j.services.TextDocumentService
import java.net.URI
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.Command as LspCommand
import org.eclipse.lsp4j.jsonrpc.messages.Either as LspEither

/** Translates the client's requests about documents to the analysis, and its results back. */
class BDocumentService(
    private val documents: OpenDocuments,
    private val checks: DocumentChecks,
    private val communicator: CommunicatorInterface,
    private val analysis: Analysis<Project>,
) : TextDocumentService {
    /**
     * The document open notification is sent from the client to the server to signal newly opened
     * text documents. The document's truth is now managed by the client.
     */
    override fun didOpen(params: DidOpenTextDocumentParams) {
        val file = fileOf(params.textDocument.uri)
        documents.update(file, params.textDocument.text)
        checks.check(file)
    }

    /** The document save notification is sent from the client to the server when the document was saved. */
    override fun didSave(params: DidSaveTextDocumentParams) {
        val file = fileOf(params.textDocument.uri)
        communicator.sendDebugMessage("document $file was saved", MessageType.Info)
        checks.check(file)
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> =
        CompletableFuture.supplyAsync {
            val file = fileOf(params.textDocument.uri)
            val text = documents.textOf(file) ?: return@supplyAsync null
            // A failing hover shows nothing rather than an error in the editor.
            val markdown =
                Either
                    .catch { analysis.hover(Project.of(file, text), SourcePosition(params.position.line, params.position.character)) }
                    .onLeft { communicator.sendDebugMessage("hover failed: ${it.message}", MessageType.Info) }
                    .getOrNull() ?: return@supplyAsync null
            Hover(MarkupContent(MarkupKind.MARKDOWN, markdown.text))
        }

    override fun completion(params: CompletionParams): CompletableFuture<LspEither<MutableList<CompletionItem>, CompletionList>> =
        CompletableFuture.supplyAsync {
            val file = fileOf(params.textDocument.uri)
            // A failing completion offers nothing rather than an error in the editor.
            val items =
                Either
                    .catch {
                        analysis
                            .complete(
                                documents.projectOf(file),
                                SourcePosition(params.position.line, params.position.character),
                            ).map { it.toLsp() }
                    }.onLeft { communicator.sendDebugMessage("completion failed: ${it.message}", MessageType.Info) }
                    .getOrElse { emptyList() }
            LspEither.forRight(CompletionList(false, items))
        }

    /**
     * Offers the ProB commands as source actions of the whole file, and only when asked for explicitly,
     * so clients show no lightbulb on every line.
     */
    override fun codeAction(params: CodeActionParams): CompletableFuture<List<LspEither<LspCommand, CodeAction>>> {
        val uri = params.textDocument.uri
        val wanted =
            params.context.triggerKind != CodeActionTriggerKind.Automatic &&
                params.context.only?.any { CodeActionKind.Source.startsWith(it) } ?: true
        val actions =
            Command.entries.filter { wanted && it.title != null }.map { command ->
                LspEither.forRight<LspCommand, CodeAction>(
                    CodeAction(command.title).apply {
                        kind = CodeActionKind.Source
                        this.command = LspCommand(command.title, command.id, listOf(uri))
                    },
                )
            }
        return CompletableFuture.completedFuture(actions)
    }

    /** Keeps a closed document's text, as the files that refer to it may still be open. */
    override fun didClose(params: DidCloseTextDocumentParams) {
        communicator.sendDebugMessage("document ${URI(params.textDocument.uri).path} was closed", MessageType.Info)
    }

    /** Keeps the editor's text for hovers and completions; checking happens on save, since ProB reads the files on disk. */
    override fun didChange(params: DidChangeTextDocumentParams) {
        params.contentChanges.lastOrNull()?.let { documents.update(fileOf(params.textDocument.uri), it.text) }
    }
}
