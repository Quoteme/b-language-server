package b.language.server.server

import b.language.server.analysis.AnalysisDispatcher
import b.language.server.analysis.Analysis
import b.language.server.analysis.Problem
import b.language.server.client.DummyCommunicator
import b.language.server.config.Settings
import b.language.server.project.Project
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

class BDocumentServiceTest {
    /** What each test started, closed afterwards to end its ProB processes. */
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun close() = opened.asReversed().forEach { it.close() }

    class DummyServer : ServerInterface {
        override fun getDocumentSettings(uri: String): CompletableFuture<Settings> {
            val result = CompletableFuture<Settings>()
            result.complete(Settings())
            return result
        }

        override fun removeDocumentSettings(uri: String) {
            TODO("Not yet implemented")
        }
    }

    /**
     * Fakes the behavior of the user by returning no errors when called the second time
     * The user fixed the errors
     */
    class FixedAfterFirstCheck(
        private val real: Analysis<Project>,
    ) : Analysis<Project> by real {
        private var counter = 0

        override fun check(
            project: Project,
            settings: Settings,
        ): List<Problem> = if (counter++ == 0) real.check(project, settings) else listOf()
    }

    @Test
    fun checkDocument_test_documentRegistered() {
        val communicator = DummyCommunicator()

        val analysis = AnalysisDispatcher().also { opened += it }
        val documentService = BDocumentService(DummyServer(), communicator, analysis).also { opened += it }

        val documentToCheck = File("src/test/resources/WD_M1.mch").absolutePath

        val expectedDocument = File("src/test/resources/WD_M1.mch").absolutePath

        documentService.checkDocument(File(documentToCheck))

        assertEquals(1, communicator.pushedDiagnostics.entries.size)
        assertEquals(
            3,
            communicator.pushedDiagnostics.entries
                .first()
                .value.size,
        )
        assertEquals(expectedDocument, communicator.pushedDiagnostics.keys.first())
    }

    @Test
    fun checkDocument_test_subDocument_displayed_properly() {
        val communicator = DummyCommunicator()

        val analysis = AnalysisDispatcher().also { opened += it }
        val documentService = BDocumentService(DummyServer(), communicator, analysis).also { opened += it }

        val documentToCheck = File("src/test/resources/WD_M2.mch").absolutePath

        val expectedDocument = File("src/test/resources/WD_M1.mch").absolutePath

        documentService.checkDocument(File(documentToCheck))

        communicator.pushedDiagnostics.clear()

        documentService.checkDocument(File(documentToCheck))

        assertEquals(2, communicator.pushedDiagnostics.entries.size)
        assertEquals(
            3,
            communicator.pushedDiagnostics.entries
                .first()
                .value.size,
        )
        assertEquals(expectedDocument, communicator.pushedDiagnostics.keys.first())
    }

    @Test
    fun checkDocument_test_unregistered_document_after_no_errors() {
        val communicator = DummyCommunicator()

        val analysis = AnalysisDispatcher().also { opened += it }
        val documentService = BDocumentService(DummyServer(), communicator, FixedAfterFirstCheck(analysis)).also { opened += it }

        documentService.checkDocument(File(File("src/test/resources/WD_M2.mch").absolutePath))

        documentService.checkDocument(File(File("src/test/resources/WD_M2.mch").absolutePath))

        assertEquals(
            emptyList(),
            communicator.pushedDiagnostics.entries
                .first()
                .value,
        )
    }

    /*
     * Write Tests:
     * Test different options you can turn on and off
     * strict, WD, performance
     * Included files have errors and errors are mapped properly
     * Included files have no errors
     * Included files have
     */
}
