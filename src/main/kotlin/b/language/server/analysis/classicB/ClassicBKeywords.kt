package b.language.server.analysis.classicB

import b.language.server.analysis.documentation.KeywordDocumentation
import b.language.server.analysis.documentation.Link

/** Classical B keywords that ProB's summary only mentions in prose. */
object ClassicBKeywords {
    private val all: Map<String, KeywordDocumentation> =
        run {
            val header = Link.syntax("Machine header")
            val sections = Link.syntax("Machine sections")
            val inclusion = Link.syntax("Machine inclusion")
            mapOf(
                "MODEL" to KeywordDocumentation("MODEL name", "A synonym for MACHINE.", null, header),
                "SYSTEM" to KeywordDocumentation("SYSTEM name", "A synonym for MACHINE.", null, header),
                "EVENTS" to KeywordDocumentation("EVENTS e;…", "A synonym for OPERATIONS.", null, header),
                "ref" to KeywordDocumentation("event ref abstract_event", "Atelier B's keyword for event refinement.", null, header),
                "ABSTRACT_CONSTANTS" to
                    KeywordDocumentation("ABSTRACT_CONSTANTS c,…", "Constants that need not be implemented.", null, sections),
                "ABSTRACT_VARIABLES" to
                    KeywordDocumentation("ABSTRACT_VARIABLES v,…", "Variables that need not be implemented.", null, sections),
                "VISIBLE_CONSTANTS" to KeywordDocumentation("VISIBLE_CONSTANTS c,…", "A synonym for CONCRETE_CONSTANTS.", null, sections),
                "VISIBLE_VARIABLES" to KeywordDocumentation("VISIBLE_VARIABLES v,…", "A synonym for CONCRETE_VARIABLES.", null, sections),
                "LOCAL_OPERATIONS" to
                    KeywordDocumentation("LOCAL_OPERATIONS O;…", "Operations only available inside an implementation.", null, sections),
                "VALUES" to
                    KeywordDocumentation(
                        "VALUES c = E;…",
                        "Values of constants and deferred sets in an implementation; ProB supports it partially.",
                        null,
                        sections,
                    ),
                "IMPORTS" to
                    KeywordDocumentation(
                        "IMPORTS M,…",
                        "Machines imported by an implementation.",
                        "IMPLEMENTATION Counter_i\nREFINES Counter\nIMPORTS Storage",
                        inclusion,
                    ),
            )
        }

    operator fun get(word: String): KeywordDocumentation? = all[word]

    val entries: Map<String, KeywordDocumentation> get() = all
}
