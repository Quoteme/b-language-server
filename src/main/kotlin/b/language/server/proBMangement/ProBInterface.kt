package b.language.server.proBMangement

import b.language.server.dataStorage.Settings
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import java.io.File

interface ProBInterface {

    /**
     * Checks the given document with the help of ProB
     * @param file the source to check
     * @param settings the settings for this document
     * @return a list of all problems found
     */
    fun checkDocument(file : File, settings: Settings) : List<Diagnostic>

    /**
     * Documents what is written at a position of a document
     * @param file the document
     * @param text the document's text as the editor has it
     * @param position the position in the text
     * @return Markdown, or null if there is nothing to show
     */
    fun hover(file : File, text : String, position : Position) : String? = null

}