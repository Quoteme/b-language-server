package b.language.server.generator

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File
import java.net.URI

private const val SYNTAX = "https://prob.hhu.de/w/index.php?title=Summary_of_B_Syntax"

/** One documented key. [primary] tells whether [key] names the entry rather than being part of it, like THEN in IF … THEN … END. */
data class Entry(
    val key: String,
    val title: String,
    val description: String,
    val link: String,
    val source: String,
    val primary: Boolean,
    val example: String? = null,
)

/** An entry of a [section] of ProB's Summary of B Syntax. */
private fun entry(key: String, title: String, description: String, section: String, primary: Boolean) =
    Entry(key, title, description, "$SYNTAX#${section.replace(' ', '_')}", "ProB, Summary of B Syntax: $section", primary)

private fun keyword(title: String, description: String, section: String) =
    entry(title.substringBefore(' '), title, description, section, true)

/** Keywords missing from the tables of the Summary of B Syntax, described by hand. */
val KEYWORDS =
    listOf(
        keyword("MODEL name", "A synonym for MACHINE.", "Machine header"),
        keyword("SYSTEM name", "A synonym for MACHINE.", "Machine header"),
        keyword("EVENTS e;…", "A synonym for OPERATIONS.", "Machine header"),
        keyword("ref abstract_event", "Atelier B's keyword for event refinement.", "Machine header"),
        keyword("ABSTRACT_CONSTANTS c,…", "Constants that need not be implemented.", "Machine sections"),
        keyword("ABSTRACT_VARIABLES v,…", "Variables that need not be implemented.", "Machine sections"),
        keyword("VISIBLE_CONSTANTS c,…", "A synonym for CONCRETE_CONSTANTS.", "Machine sections"),
        keyword("VISIBLE_VARIABLES v,…", "A synonym for CONCRETE_VARIABLES.", "Machine sections"),
        keyword("LOCAL_OPERATIONS O;…", "Operations only available inside an implementation.", "Machine sections"),
        keyword("VALUES c = E;…", "Values of constants and deferred sets in an implementation; ProB supports it partially.", "Machine sections"),
        keyword("IMPORTS M,…", "Machines imported by an implementation.", "Machine inclusion"),
    )

/** Sections of explanations rather than syntax. */
private val PROSE = setOf("Differences with AtelierB/B4Free", "Other notes", "Free Types", "Event-B Syntax", "Comments and Pragmas", "Identifiers")

/** Words that stand for an argument of a construct, like S in S\/T. */
private val PLACEHOLDER =
    Regex(
        "[A-Za-z]|[A-Za-z][0-9n]|ID|tl|tr|Dom|Ran|Pair|OP|BODY|NAME[0-9]?|LBL|DESC|PATH|PRAGMA|VALUE|SETNAME|OPNAME|FILE|Expression|" +
            "E[0-9n]?|G[0-9n]?|P[0-9n]?|x[0-9n]?|r[0-9]|ss|cx|cy|cv|cw|e[0-9]|x[0-9]|astring|tstring|Expr",
    )
private val LOWERCASE_WORDS = setOf("mod", "or", "not", "btrue", "bfalse", "skip")
private val WORD = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val OPERATOR = Regex("""[^\sA-Za-z0-9_(),.\[\]{}'"`$]+""")
private val COLUMNS = Regex("""\s{2,}""")

/** The keywords, built-in functions and operators of an entry's [syntax]. */
fun keysOf(syntax: String): List<String> {
    val keys = linkedSetOf<String>()
    // "S-T or S \ T" lists alternatives; in "P or Q", or is the operator.
    val alternatives = if (syntax == "P or Q") listOf(syntax) else syntax.split(Regex("""\s+or\s+"""))
    for (alternative in alternatives.map { it.replace(Regex("\"[^\"]*\""), "") }) {
        for (word in WORD.findAll(alternative).map { it.value }) {
            val keyword = word.matches(Regex("[A-Z][A-Z0-9_]+"))
            val function = word[0].isLowerCase() && "$word(" in alternative
            if (!word.matches(PLACEHOLDER) && (keyword || function || word in LOWERCASE_WORDS)) keys += word
        }
        keys += OPERATOR.findAll(alternative).map { it.value }.filter { it !in listOf("...", "…", "_", "•") }
    }
    // Constructs made of brackets and dots alone: n..m, r[S], [E], E'ID.
    if (keys.isEmpty()) keys += Regex("""(?<!\.)\.\.(?!\.)|[\['<>]""").findAll(syntax).map { it.value }
    return keys.toList()
}

/** The entries of the tables in the wiki text of ProB's Summary of B Syntax. */
fun parseSyntaxSummary(wikitext: String): List<Entry> {
    val entries = mutableListOf<Entry>()
    var section = "Summary of B Syntax"
    var inTable = false
    for (rawLine in wikitext.lines()) {
        var line = rawLine
        Regex("""^===\s*(.+?)\s*===\s*$""").find(line)?.let {
            section = it.groupValues[1]
            continue
        }
        if ("<pre>" in line) {
            inTable = true
            line = line.substringAfter("<pre>")
        }
        if ("</pre>" in line) {
            inTable = false
            continue
        }
        if (!inTable || section in PROSE || line.isBlank() || !line.startsWith(" ") || line.startsWith(" ".repeat(12))) continue
        val columns = line.trim().split(COLUMNS, limit = 2)
        var syntax = columns[0]
        var description = columns.getOrElse(1) { "" }
        // Machine sections have three columns: keyword, its argument, a description in parentheses.
        if (section == "Machine sections") {
            val argument = description.split(COLUMNS, limit = 2)
            syntax = "$syntax ${argument[0]}"
            description = argument.getOrNull(1)?.trim('(', ')').orEmpty()
        }
        // Explanations continued inside the tables, such as "mirror, infix are recognised by …".
        if (syntax.startsWith("-") || Regex("""\b[a-z]{3,}\b""").findAll(syntax).count() >= 4) continue
        val keys = keysOf(syntax)
        // The first key names the construct.
        keys.mapTo(entries) { entry(it, syntax, description, section, it == keys[0]) }
    }
    return entries
}

/** Adds to [entries] their usage examples, given as a JSON object from title to example. */
fun withExamples(entries: List<Entry>, examples: String): List<Entry> {
    val byTitle = Gson().fromJson(examples, JsonObject::class.java)
    return entries.map { it.copy(example = byTitle[it.title]?.asString) }
}

/** Writes the documentation of the current wiki page to the file given as the only argument. */
fun main(args: Array<String>) {
    val entries = parseSyntaxSummary(URI("$SYNTAX&action=raw").toURL().readText()) + KEYWORDS
    val examples = Entry::class.java.getResource("/examples.json")!!.readText()
    File(args.single()).writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(withExamples(entries, examples)) + "\n")
}
