package b.language.server.project.eventB

import b.language.server.project.Location
import b.language.server.project.Project
import de.be4.eventb.core.parser.BException
import de.be4.eventb.core.parser.EventBLexerException
import de.be4.eventb.core.parser.EventBParseException
import de.be4.eventb.core.parser.EventBParser
import de.be4.eventb.core.parser.lexer.LexerException
import de.be4.eventb.core.parser.node.AContextParseUnit
import de.be4.eventb.core.parser.node.AMachineParseUnit
import de.be4.eventb.core.parser.node.PParseUnit
import de.be4.eventb.core.parser.node.TIdentifierLiteral
import de.be4.eventb.core.parser.node.Token
import java.io.File

/**
 * Event-B machines and contexts written in Camille notation (`machine m0 sees c0 …`), each in its own
 * file next to the files that reference it. ProB only loads Event-B from Rodin projects, so the
 * components are parsed here.
 */
class EventBProject(
    override val mainFile: File,
    override val text: String,
) : Project {
    enum class ComponentKind(
        val keyword: String,
    ) {
        MACHINE("machine"),
        CONTEXT("context"),
    }

    /** A component file and its parse tree. */
    class Component(
        val file: File,
        val unit: PParseUnit,
    ) {
        val kind: ComponentKind = if (unit is AMachineParseUnit) ComponentKind.MACHINE else ComponentKind.CONTEXT

        /** The name as declared, with its place in the file. */
        val name: TIdentifierLiteral =
            when (unit) {
                is AMachineParseUnit -> unit.name
                is AContextParseUnit -> unit.name
                else -> error("not a machine or context: $unit")
            }
    }

    /** Why a component could not be loaded. */
    data class LoadError(
        val location: Location,
        val message: String,
    )

    sealed interface Loading {
        data class Loaded(
            val component: Component,
        ) : Loading

        data class Failed(
            val errors: List<LoadError>,
        ) : Loading
    }

    private val components = LinkedHashMap<String, Component>()

    /** Loads the main file. */
    fun loadMain(): Loading = load(mainFile, null, null)

    /** A component loaded before, by name. */
    fun component(name: String): Component? = components[name]

    /** Finds a referenced component next to the file that references it, and loads it. */
    fun resolve(
        from: Component,
        reference: TIdentifierLiteral,
    ): Loading {
        components[reference.text]?.let { return Loading.Loaded(it) }
        val dir = from.file.absoluteFile.parentFile
        val candidates = listOf(from.file.extension, "mch").distinct().map { File(dir, "${reference.text}.$it") }
        return load(candidates.firstOrNull { it.isFile } ?: candidates.first(), from, reference)
    }

    private fun load(
        file: File,
        referencedFrom: Component?,
        reference: Token?,
    ): Loading {
        if (!file.isFile) {
            if (referencedFrom == null || reference == null) return Loading.Failed(emptyList())
            return failed(referencedFrom.file, reference, "Component '${reference.text}' not found, expected it in ${file.name}")
        }
        val text = file.readText()
        val unit =
            try {
                EventBParser().parse(text).pParseUnit
            } catch (e: BException) {
                return Loading.Failed(listOf(parseError(file, Project.firstWord(text), e)))
            }
        if (unit !is AMachineParseUnit && unit !is AContextParseUnit) return Loading.Failed(emptyList())
        val component = Component(file, unit)
        components[component.name.text] = component
        return Loading.Loaded(component)
    }

    private fun failed(
        file: File,
        token: Token,
        message: String,
    ) = Loading.Failed(listOf(LoadError(Location.ofToken(file, token.line, token.pos, token.text.length), message)))

    private fun parseError(
        file: File,
        kind: String?,
        e: BException,
    ): LoadError {
        // SableCC prefixes messages with "[line,pos]"; the range already says where.
        fun message(raw: String?) = raw?.replace(Regex("""^\[\d+,\d+]\s*"""), "") ?: "Syntax error"

        fun at(
            line: Int,
            column: Int,
            length: Int,
            text: String,
        ) = LoadError(Location.ofToken(file, line, column, length), text)
        return when (val cause = e.cause) {
            is EventBParseException -> {
                val token = cause.token
                val hint =
                    when {
                        token == null -> null
                        kind == ComponentKind.MACHINE.keyword && token.text in CONTEXT_CLAUSES -> CONTEXT_HINT
                        kind == ComponentKind.CONTEXT.keyword && token.text in MACHINE_CLAUSES -> MACHINE_HINT
                        else -> null
                    }
                val text = hint?.let { "$it (${message(cause.message)})" } ?: message(cause.message)
                if (token != null) at(token.line, token.pos, token.text.length, text) else at(1, 1, 1, text)
            }

            is EventBLexerException -> {
                at(cause.lastLine, cause.lastPos, cause.lastText?.length ?: 1, message(cause.message))
            }

            is LexerException -> {
                val position = Regex("""\[(\d+),(\d+)]\s*(.*)""").find(cause.message ?: "")
                if (position != null) {
                    val (line, pos, text) = position.destructured
                    at(line.toInt(), pos.toInt(), 1, text)
                } else {
                    at(1, 1, 1, cause.message ?: "Syntax error")
                }
            }

            else -> {
                at(1, 1, 1, e.message ?: "Syntax error")
            }
        }
    }

    companion object {
        /** The first keyword of a component in Camille notation. */
        val KEYWORDS = ComponentKind.entries.map { it.keyword }.toSet()

        private val CONTEXT_CLAUSES = setOf("extends", "sets", "constants", "axioms")
        private val MACHINE_CLAUSES = setOf("refines", "sees", "variables", "invariants", "variant", "events")
        private const val CONTEXT_HINT = "extends, sets, constants and axioms belong in a context, not in a machine"
        private const val MACHINE_HINT = "refines, sees, variables, invariants, variant and events belong in a machine, not in a context"
    }
}
