package b.language.server.analysis.legacy

import b.language.server.config.Settings
import org.eclipse.lsp4j.Diagnostic
import java.io.File

interface ProBInterface {
    /**
     * Checks the given document with the help of ProB
     * @param file the source to check
     * @param settings the settings for this document
     * @return a list of all problems found
     */
    fun checkDocument(
        file: File,
        settings: Settings,
    ): List<Diagnostic>

    /**
     * Model checks the given document for deadlocks and invariant violations
     * @return the problems found, and a summary of the run
     */
    fun modelCheck(
        file: File,
        settings: Settings,
    ): Pair<List<Diagnostic>, String> = emptyList<Diagnostic>() to "Model checking is not supported"

    /** The proof obligations of an Event-B document from its last check. */
    fun proofObligations(file: File): List<ProofObligation> = emptyList()

    /** Hover texts for the identifiers of a classical B document, from its last check. */
    fun identifiers(file: File): Map<String, String> = emptyMap()
}
