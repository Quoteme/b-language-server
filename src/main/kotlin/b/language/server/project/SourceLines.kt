package b.language.server.project

import java.io.File

/** The lines of a source file, searched for declarations by the section they are in. */
class SourceLines(
    private val file: File,
) {
    private val lines by lazy { file.readLines() }

    /**
     * The first match of [pattern] at or after the line that starts the section [keyword]
     * (ignoring case and an ABSTRACT_ or CONCRETE_ prefix); the keyword itself is not a match.
     */
    fun find(
        pattern: Regex,
        keyword: String,
    ): Location? {
        val section = Regex("""(?i)^\s*(?:ABSTRACT_|CONCRETE_)?${Regex.escape(keyword)}\b""")
        val start = lines.indexOfFirst { section.containsMatchIn(it) }
        if (start < 0) return null
        for (index in start until lines.size) {
            val line =
                if (index != start) {
                    lines[index]
                } else {
                    lines[index].let { text ->
                        section.find(text)?.let { text.replaceRange(it.range, " ".repeat(it.value.length)) } ?: text
                    }
                }
            pattern.find(line)?.let { return Location.at(file, SourcePosition(index, it.range.first)) }
        }
        return null
    }

    companion object {
        /** Matches [name] as a whole identifier, which may be primed. */
        fun identifier(name: String) = Regex("""(?<![\w'])${Regex.escape(name)}(?![\w'])""")
    }
}
