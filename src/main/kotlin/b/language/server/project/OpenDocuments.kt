package b.language.server.project

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The text of the documents open in the editor, which may differ from the files on disk until they are saved. */
class OpenDocuments {
    private val texts = ConcurrentHashMap<File, String>()

    fun update(
        file: File,
        text: String,
    ) {
        texts[file] = text
    }

    /** The editor's text of [file], or else its content on disk; null if it exists in neither. */
    fun textOf(file: File): String? = texts[file] ?: file.takeIf { it.isFile }?.readText()
}
