package b.language.server.analysis.classicB

import arrow.core.Either
import b.language.server.analysis.CandidateKind
import b.language.server.analysis.Markdown
import b.language.server.analysis.documentation.CodeLanguage
import b.language.server.analysis.documentation.DocumentationCard
import b.language.server.analysis.documentation.Link
import de.prob.animator.command.FormulaTypecheckCommand
import de.prob.animator.command.GetMachineIdentifiersCommand
import de.prob.animator.command.GetMachineIdentifiersCommand.Category
import de.prob.animator.domainobjects.FormulaExpand
import de.prob.statespace.StateSpace

/** What an identifier of a classical B machine is. */
enum class IdentifierKind(
    val completion: CandidateKind,
) {
    DEFERRED_SET(CandidateKind.CLASS),
    ENUMERATED_SET(CandidateKind.ENUM),
    ELEMENT(CandidateKind.ENUM_MEMBER),
    CONSTANT(CandidateKind.CONSTANT),
    VARIABLE(CandidateKind.VARIABLE),
    FREE_TYPE(CandidateKind.CLASS),
    DEFINITION(CandidateKind.VALUE),
    OPERATION(CandidateKind.FUNCTION),
}

/** An identifier of a machine, and its documentation. */
data class IdentifierCard(
    val name: String,
    val kind: IdentifierKind,
    val documentation: Markdown,
)

/** The identifiers of a classical B machine as ProB sees them, with their types, in declaration order. */
class IdentifierCatalog private constructor(
    private val cards: Map<String, IdentifierCard>,
) {
    operator fun get(name: String): IdentifierCard? = cards[name]

    val all: Collection<IdentifierCard> get() = cards.values

    companion object {
        val EMPTY = IdentifierCatalog(emptyMap())

        private val SECTIONS = Link.syntax("Machine sections")
        private val FREE_TYPES = Link.syntax("Free Types")
        private val DEFINITIONS = Link.syntax("Definitions")

        /** Describes every identifier of the machine loaded in [stateSpace]. */
        fun of(stateSpace: StateSpace): IdentifierCatalog {
            val cards = LinkedHashMap<String, IdentifierCard>()

            fun names(category: Category) = GetMachineIdentifiersCommand(category).also { stateSpace.execute(it) }.identifiers

            /** The type ProB infers for [name], unless it cannot parse or type it. */
            fun type(name: String): String? =
                Either
                    .catch {
                        FormulaTypecheckCommand(stateSpace.model.parseFormula(name, FormulaExpand.TRUNCATE))
                            .also { stateSpace.execute(it) }
                            .result
                            .takeIf { it.isOk }
                            ?.type
                    }.getOrNull()

            fun typed(name: String) = type(name)?.let { "$name : $it" } ?: name

            fun put(
                name: String,
                kind: IdentifierKind,
                signature: String,
                text: String,
                link: Link = SECTIONS,
            ) {
                cards[name] =
                    IdentifierCard(
                        name,
                        kind,
                        DocumentationCard(CodeLanguage.CLASSIC_B, signature, listOf(text), links = listOf(link)).render(),
                    )
            }

            val sets = names(Category.SETS)
            val elements = names(Category.SET_CONSTANTS)
            for (name in sets) {
                val members = elements.filter { type(it) == name }
                if (members.isEmpty()) {
                    put(
                        name,
                        IdentifierKind.DEFERRED_SET,
                        "(deferred set) $name",
                        "A deferred set, declared in SETS: a non-empty set whose elements are not given.",
                    )
                } else {
                    put(
                        name,
                        IdentifierKind.ENUMERATED_SET,
                        "(enumerated set) $name = {${members.joinToString(", ")}}",
                        "An enumerated set, declared in SETS.",
                    )
                }
            }
            for (name in elements) put(name, IdentifierKind.ELEMENT, "(element) ${typed(name)}", "An element of an enumerated set.")
            for (name in names(Category.CONSTANTS)) {
                put(name, IdentifierKind.CONSTANT, "(constant) ${typed(name)}", "A constant; its type follows from PROPERTIES.")
            }
            for (name in names(Category.VARIABLES)) {
                put(name, IdentifierKind.VARIABLE, "(variable) ${typed(name)}", "A variable; its type follows from INVARIANT.")
            }
            for (name in names(Category.FREETYPES)) {
                put(name, IdentifierKind.FREE_TYPE, "(free type) $name", "A free type, declared in FREETYPES.", FREE_TYPES)
            }
            for (name in names(Category.DEFINITIONS)) {
                if (name !in
                    cards
                ) {
                    put(name, IdentifierKind.DEFINITION, "(definition) $name", "A definition, expanded where it is used.", DEFINITIONS)
                }
            }
            val machine = stateSpace.loadedMachine
            for (name in names(Category.OPERATIONS)) {
                val info = machine.getMachineOperationInfo(name) ?: continue

                fun list(names: List<String>) = names.joinToString(", ") { p -> info.typeMap[p]?.let { "$p : ${prologType(it)}" } ?: p }
                val signature =
                    (if (info.outputParameterNames.isEmpty()) "" else "${list(info.outputParameterNames)} <-- ") +
                        name + (if (info.parameterNames.isEmpty()) "" else "(${list(info.parameterNames)})")
                val lines = mutableListOf("An operation of the machine.")
                if (info.readVariables.isNotEmpty()) lines += "Reads: " + info.readVariables.joinToString(", ") { "`$it`" }
                if (info.writtenVariables.isNotEmpty()) lines += "Writes: " + info.writtenVariables.joinToString(", ") { "`$it`" }
                put(name, IdentifierKind.OPERATION, "(operation) $signature", lines.joinToString("  \n"))
            }
            return IdentifierCatalog(cards)
        }

        /** ProB's internal notation of a type, such as set(couple(integer,global('S'))), in B: POW(INTEGER*S). */
        private fun prologType(type: String): String {
            var result = type
            result = result.replace(Regex("""global\('?([^')]+)'?\)"""), "$1")
            result =
                result
                    .replace("integer", "INTEGER")
                    .replace("boolean", "BOOL")
                    .replace("string", "STRING")
                    .replace("real", "REAL")
            repeat(8) {
                result =
                    result.replace(Regex("""couple\(([^()]*(?:\([^()]*\))*[^()]*),([^()]*(?:\([^()]*\))*[^()]*)\)""")) {
                        "(${it.groupValues[1]}*${it.groupValues[2]})"
                    }
                result = result.replace(Regex("""set\(([^()]*(?:\([^()]*\))*[^()]*)\)""")) { "POW(${it.groupValues[1]})" }
                result = result.replace(Regex("""seq\(([^()]*(?:\([^()]*\))*[^()]*)\)""")) { "seq(${it.groupValues[1]})" }
            }
            return result
        }
    }
}
