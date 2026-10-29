package b.language.server

import b.language.server.config.Settings
import java.util.concurrent.CompletableFuture

interface ServerInterface {
    fun getDocumentSettings(uri: String): CompletableFuture<Settings>

    fun removeDocumentSettings(uri: String)
}
