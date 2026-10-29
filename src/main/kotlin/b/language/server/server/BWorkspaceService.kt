package b.language.server.server

import b.language.server.client.Communicator
import b.language.server.config.Settings
import com.google.gson.JsonObject
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class BWorkspaceService(
    private val server: Server,
    private val communicator: Communicator,
) : WorkspaceService {
    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any?> =
        (server.textDocumentService as BDocumentService).executeCommand(params.command, params.arguments.orEmpty())

    /**
     * The watched files notification is sent from the client to the server when
     * the client detects changes to file watched by the language client.
     */
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams?) {
        communicator.sendDebugMessage("----------changed watched files", MessageType.Info)

        // Not needed
    }

    /**
     * A notification sent from the client to the server to signal the change of
     * configuration settings.
     */
    override fun didChangeConfiguration(params: DidChangeConfigurationParams?) {
        communicator.sendDebugMessage("received change in configuration settings", MessageType.Info)
        // A client that can be asked for settings is asked on every check, so only the pushed settings need storing.
        if (!server.configurationAbility) {
            (params?.settings as? JsonObject)?.let { server.globalSettings = server.readSettings(it) }
        }
    }
}
