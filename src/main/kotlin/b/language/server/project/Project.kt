package b.language.server.project

import b.language.server.project.classicB.ClassicBProject
import b.language.server.project.eventB.EventBProject
import java.io.File

/** A B model, given by the file the user works on, and the files it depends on: classical B or Event-B so far. */
interface Project {
    val mainFile: File

    /** The main file's text as the editor has it. */
    val text: String

    companion object {
        private val FIRST_WORD = Regex("""\A(?:\s|//[^\n]*|/\*[\s\S]*?\*/)*([A-Za-z_]+)""")

        /** The first keyword of [text], skipping whitespace and comments. */
        fun firstWord(text: String): String? = FIRST_WORD.find(text)?.groupValues?.get(1)

        /**
         * The project of [file]. Event-B in Camille notation shares the extension .mch with classical B;
         * its keywords are lowercase (`machine`, `context`), so classical B (`MACHINE`) never matches.
         */
        fun of(
            file: File,
            text: String,
        ): Project =
            if (firstWord(text) in EventBProject.KEYWORDS) {
                EventBProject(file, text)
            } else {
                ClassicBProject(file, text)
            }
    }
}
