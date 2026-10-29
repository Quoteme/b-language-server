package b.language.server.temporary

import b.language.server.client.CommunicatorInterface
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.MessageType

/** Records what the server shows and publishes, in order. */
class RecordingCommunicator : CommunicatorInterface {
    val diagnostics = LinkedHashMap<String, List<Diagnostic>>()
    val messages = mutableListOf<String>()

    override fun publishDiagnostics(
        target: String,
        diagnostics: List<Diagnostic>,
    ) {
        this.diagnostics[target] = diagnostics
    }

    override fun sendDebugMessage(
        message: String,
        severity: MessageType,
    ) = Unit

    override fun showMessage(
        message: String,
        severity: MessageType,
    ) {
        messages += "$severity: $message"
    }

    override fun setDebugMode(mode: Boolean) = Unit

    override fun bufferDebugMessage(
        message: String,
        severity: MessageType,
    ) = Unit

    fun clear() {
        diagnostics.clear()
        messages.clear()
    }
}
