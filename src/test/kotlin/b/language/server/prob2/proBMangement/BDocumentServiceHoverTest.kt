package b.language.server.prob2.proBMangement

import DummyCommunicator
import b.language.server.BDocumentService
import b.language.server.dataStorage.Settings
import b.language.server.proBMangement.ProBInterface
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

class BDocumentServiceHoverTest {
    private val uri = "file:///M.mch"

    /** Shows the text it was asked about. */
    private class Echo(private val failing: Boolean = false) : ProBInterface {
        override fun checkDocument(file: File, settings: Settings) = listOf<Diagnostic>()

        override fun hover(file: File, text: String, position: Position): String {
            check(!failing)
            return text
        }
    }

    private fun service(proB: ProBInterface) =
        BDocumentService(BDocumentServiceTest.DummyServer(), DummyCommunicator(), proB)

    private fun BDocumentService.open(text: String) =
        didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "classicalb", 1, text)))

    private fun BDocumentService.hoverText() =
        hover(HoverParams(TextDocumentIdentifier(uri), Position(0, 0))).get()?.contents?.right?.value

    @Test
    fun hover_readsTheEditorsTextAfterAnUnsavedChange() {
        val service = service(Echo())
        service.open("old")

        service.didChange(
            DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2), listOf(TextDocumentContentChangeEvent("new"))),
        )

        assertEquals("new", service.hoverText())
    }

    @Test
    fun hover_ofADocumentThatIsNotOpen_showsNothing() {
        assertEquals(null, service(Echo()).hoverText())
    }

    @Test
    fun failingHover_showsNothing() {
        val service = service(Echo(failing = true))
        service.open("text")

        assertEquals(null, service.hoverText())
    }
}
