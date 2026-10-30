package b.language.server.analysis.documentation

/** One line of source text, and what is written at a column of it. */
class LineText(
    private val source: String,
) {
    /** The word (identifier or `@label`) under [column], or null. */
    fun wordAt(column: Int): String? {
        if (column !in source.indices) return null
        val char = source[column]
        if (!(isWordChar(char) || char == '@') || isSymbolLetter(char)) return null
        var start = column
        while (start > 0 && (isWordChar(source[start - 1]) || source[start - 1] == '@') && !isSymbolLetter(source[start - 1])) start--
        var end = column
        while (end < source.length && isWordChar(source[end]) && !isSymbolLetter(source[end])) end++
        return source.substring(start, end).takeIf { it.isNotBlank() && it != "'" }
    }

    /** The longest operator of [table] that covers [column]. */
    fun operatorAt(
        column: Int,
        table: OperatorTable,
    ): List<OperatorEntry>? {
        if (column !in source.indices) return null
        for (length in 5 downTo 1) {
            for (start in (column - length + 1)..column) {
                if (start < 0 || start + length > source.length) continue
                val candidate = source.substring(start, start + length)
                if (candidate.isBlank() || (length > 1 && candidate.all { it.isLetterOrDigit() })) continue
                table[candidate]?.let { return it }
            }
        }
        return null
    }

    /** The character at [column], if any. */
    fun charAt(column: Int): Char? = source.getOrNull(column)

    /** The part of the word that ends at [column], which a completion completes. */
    fun prefixBefore(column: Int): String = source.take(column).takeLastWhile(::isWordChar)

    companion object {
        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '\''

        /** Letters that are mathematical symbols, such as ℕ, rather than parts of a name. */
        private fun isSymbolLetter(c: Char) = c in "ℕℤℙλ"

        /** The line [line] of [text], or an empty line beyond its end. */
        fun of(
            text: String,
            line: Int,
        ): LineText? = text.lines().getOrNull(line)?.let(::LineText)
    }
}
