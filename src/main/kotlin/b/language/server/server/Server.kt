package b.language.server.server

import b.language.server.analysis.legacy.ProBKernelManager
import b.language.server.client.Communicator
import b.language.server.config.Settings
import com.google.gson.JsonObject
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionOptions
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.ExecuteCommandOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.WorkspaceFoldersOptions
import org.eclipse.lsp4j.WorkspaceServerCapabilities
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import kotlin.collections.HashMap
import kotlin.system.exitProcess

class Server :
    LanguageServer,
    ServerInterface {
    private val textDocumentService: TextDocumentService = BDocumentService(this, Communicator, ProBKernelManager(Communicator))
    private val bWorkspaceService: WorkspaceService = BWorkspaceService(this, Communicator)
    private lateinit var languageClient: LanguageClient
    var globalSettings: Settings = Settings()
    val documentSettings: HashMap<String, CompletableFuture<Settings>> = HashMap()
    var configurationAbility: Boolean = true

    override fun initialize(params: InitializeParams?): CompletableFuture<InitializeResult> {
        val res = InitializeResult(ServerCapabilities())
        // ProB checks the file on disk, so only opening and saving trigger a check.
        val sync = TextDocumentSyncOptions()
        sync.openClose = true
        // Changes are kept for hovering only; ProB checks the file on disk when it is saved.
        sync.change = TextDocumentSyncKind.Full
        sync.save = Either.forRight(SaveOptions(false))
        res.capabilities.textDocumentSync = Either.forRight(sync)
        res.capabilities.workspace = WorkspaceServerCapabilities(WorkspaceFoldersOptions())
        res.capabilities.workspace.workspaceFolders.supported = true
        res.capabilities.codeActionProvider = Either.forRight(CodeActionOptions(listOf(CodeActionKind.Source)))
        res.capabilities.hoverProvider = Either.forLeft(true)
        res.capabilities.executeCommandProvider = ExecuteCommandOptions(BDocumentService.COMMANDS)
        res.capabilities.completionProvider = CompletionOptions()
        return CompletableFuture.supplyAsync { res }
    }

    /**
     * The shutdown request is sent from the client to the server. It asks the
     * server to shutdown, but to not exit (otherwise the response might not be
     * delivered correctly to the client). There is a separate exit notification
     * that asks the server to exit.
     */
    override fun shutdown(): CompletableFuture<Any> = CompletableFuture.supplyAsync { true }

    /**
     * Provides access to the textDocument services.
     */
    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    /**
     * A notification to ask the server to exit its process.
     */
    override fun exit() {
        exitProcess(0)
    }

    /**
     * Provides access to the workspace services.
     */
    override fun getWorkspaceService(): WorkspaceService = bWorkspaceService

    fun setRemoteProxy(remoteProxy: LanguageClient) {
        this.languageClient = remoteProxy
        Communicator.client = remoteProxy
    }

    /**
     * Get the settings for the current document - will fallback to global settings eventually; If setting not cached
     * method will try to get setting from the client
     * @param uri the uri of the document requested
     * @return settings of the document requested
     */
    override fun getDocumentSettings(uri: String): CompletableFuture<Settings> {
        Communicator.sendDebugMessage("requesting configuration for document: $uri", MessageType.Info)
        return if (!configurationAbility) {
            val returnValue = CompletableFuture<Settings>()
            returnValue.complete(globalSettings)

            returnValue
        } else {
            val configurationItem = ConfigurationItem()
            configurationItem.scopeUri = uri
            configurationItem.section = "prob"

            val requestedConfig = languageClient.configuration(ConfigurationParams(listOf(configurationItem)))
            documentSettings[uri] =
                CompletableFuture.allOf(requestedConfig).thenApply {
                    Settings.from(requestedConfig.get().firstOrNull() as? JsonObject)
                }
            documentSettings[uri]!!
        }
    }

    override fun removeDocumentSettings(uri: String) {
        documentSettings.remove(uri)
    }
}
