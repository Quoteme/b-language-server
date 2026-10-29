package b.language.server.server

import arrow.core.getOrElse
import b.language.server.client.CommunicatorInterface
import b.language.server.config.Settings
import com.google.gson.JsonObject
import org.eclipse.lsp4j.MessageType
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * The client's settings: asked from the client for every check if it can be asked, or else the ones it pushed last.
 * @param ask requests the client's settings for a scope
 */
class SettingsStore(
    private val communicator: CommunicatorInterface,
    private val ask: (String) -> CompletableFuture<JsonObject?>,
) {
    /** Whether the client answers requests for its settings, as it tells when it initializes. */
    @Volatile
    var canAsk = true

    @Volatile
    private var pushed = Settings()

    fun settingsOf(file: File): CompletableFuture<Settings> {
        communicator.sendDebugMessage("requesting configuration for document: $file", MessageType.Info)
        return if (canAsk) ask(file.absolutePath).thenApply(::read) else CompletableFuture.completedFuture(pushed)
    }

    /** Keeps the settings the client pushed; a client that can be asked is asked again at the next check instead. */
    fun push(json: JsonObject?) {
        if (!canAsk) json?.let { pushed = read(it) }
    }

    /** The client's settings, or the defaults if they cannot be read. */
    private fun read(json: JsonObject?): Settings =
        Settings.from(json).getOrElse {
            communicator.sendDebugMessage("ignoring invalid settings: ${it.message}", MessageType.Warning)
            Settings()
        }
}
