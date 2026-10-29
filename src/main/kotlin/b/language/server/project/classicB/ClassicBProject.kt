package b.language.server.project.classicB

import b.language.server.project.Location
import b.language.server.project.Project
import b.language.server.project.SourceLines
import java.io.File

/** A classical B machine, refinement or implementation. ProB reads the files it includes itself. */
class ClassicBProject(
    override val mainFile: File,
    override val text: String,
) : Project {
    /** The sections of a machine in which declarations are searched for. */
    enum class Section(
        val keyword: String,
    ) {
        CONSTANTS("CONSTANTS"),
        VARIABLES("VARIABLES"),
        OPERATIONS("OPERATIONS"),
        INVARIANT("INVARIANT"),
    }

    private val lines = SourceLines(mainFile)

    /** The file name's extension, which tells ProB how to read the model (mch, ref, imp, …). */
    val extension: String get() = mainFile.extension

    /** Where [name] is declared in [section] of the main file. */
    fun declaration(
        name: String,
        section: Section,
    ): Location? = lines.find(SourceLines.identifier(name), section.keyword)

    /** Where the operation [name] is defined, as in `out <-- name(p) = …`. */
    fun operation(name: String): Location? =
        lines.find(Regex("""^\s*(?:[\w,\s]+<--\s*)?${Regex.escape(name)}\s*(\(|=)"""), Section.OPERATIONS.keyword)
}
