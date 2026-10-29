package b.language.server.project

import java.io.File

/** A position in a text, 0-based like the language server protocol. */
data class SourcePosition(
    val line: Int,
    val column: Int,
)

data class SourceRange(
    val start: SourcePosition,
    val end: SourcePosition,
)

/** A range in a file. */
data class Location(
    val file: File,
    val range: SourceRange,
) {
    val start: SourcePosition get() = range.start

    companion object {
        /** The start of a file, where problems without a position are shown. */
        fun startOf(file: File) = at(file, SourcePosition(0, 0))

        /** An empty range at [position]. */
        fun at(
            file: File,
            position: SourcePosition,
        ) = Location(file, SourceRange(position, position))

        /**
         * A token as parsers report it: its 1-based line and column and its length, which spans at
         * least one character.
         */
        fun ofToken(
            file: File,
            line: Int,
            column: Int,
            length: Int,
        ): Location {
            val start = SourcePosition((line - 1).coerceAtLeast(0), (column - 1).coerceAtLeast(0))
            return Location(file, SourceRange(start, SourcePosition(start.line, start.column + length.coerceAtLeast(1))))
        }
    }
}
