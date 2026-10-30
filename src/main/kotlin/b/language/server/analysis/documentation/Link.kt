package b.language.server.analysis.documentation

/** A link to the official documentation of something. */
data class Link(
    val url: String,
    val title: String,
) {
    fun toMarkdown() = "[$title]($url)"

    companion object {
        private const val HANDBOOK = "https://www3.hhu.de/stups/handbook/rodin/current/html/"
        private const val SYNTAX = "https://prob.hhu.de/w/index.php?title=Summary_of_B_Syntax"

        /** A section of the Rodin Handbook. */
        fun handbook(
            page: String,
            section: String,
        ) = Link("$HANDBOOK$page", "Rodin Handbook, $section")

        /** A section of ProB's Summary of B Syntax. */
        fun syntax(section: String) = Link("$SYNTAX#${section.replace(' ', '_')}", "ProB, Summary of B Syntax: $section")
    }
}
