package b.language.server.server

import b.language.server.client.CommunicatorInterface
import com.google.gson.JsonObject
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

/** Translates the client's requests about the workspace: changed settings and commands. */
class BWorkspaceService(
    private val settings: SettingsStore,
    private val commands: Commands,
    private val communicator: CommunicatorInterface,
) : WorkspaceService {
    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any?> =
        commands.execute(params.command, params.arguments.orEmpty())

    /** Ignored: the server registers no file watchers, so clients send none, but lsp4j requires the method. */
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams?) {
        communicator.sendDebugMessage("changed watched files", MessageType.Info)
    }

    /**
     * A notification sent from the client to the server to signal the change of
     * configuration settings.
     */
    override fun didChangeConfiguration(params: DidChangeConfigurationParams?) {
        communicator.sendDebugMessage("received change in configuration settings", MessageType.Info)
        settings.push(params?.settings as? JsonObject)
    }
}
