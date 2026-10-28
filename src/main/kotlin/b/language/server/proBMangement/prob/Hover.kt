package b.language.server.proBMangement.prob

import de.be4.eventb.core.parser.node.TFormula
import de.prob.unicode.UnicodeTranslator
import org.eventb.core.ast.BoundIdentDecl
import org.eventb.core.ast.BoundIdentifier
import org.eventb.core.ast.FreeIdentifier
import org.eventb.core.ast.SourceLocation
import java.io.File

/**
 * Hover documentation, laid out like the hovers of other language servers: the signature in a
 * code block, then the documentation, an example, and a link to the official source.
 *
 * - Operators, built-in functions and classical B keywords: generated from the official
 *   documentation (resources hover/eventb.tsv from the Rodin Handbook, hover/classical.tsv from
 *   ProB's Summary of B Syntax), with signatures and examples from hover/examples.tsv.
 * - Event-B structure keywords: a short description with a template and an example.
 * - Identifiers: what they are, their type from Rodin's type checker (Event-B) or ProB (classical
 *   B), the formulas that constrain them, and the proof obligations of labels and events.
 */
object Hover {

    private const val HANDBOOK = "https://www3.hhu.de/stups/handbook/rodin/current/html/"
    private const val SYNTAX = "https://prob.hhu.de/w/index.php?title=Summary_of_B_Syntax"
    private const val DETAILS = " ‖ "

    // ----------------------------------------------------------------------
    // Documentation tables

    /** One entry of the generated documentation tables. */
    private class Entry(
        val title : String, val description : String, val details : String,
        val link : String, val source : String, val primary : Boolean, val symbol : String,
    ) {
        /** The entry's details by name: Definition, Types, WD, … */
        val fields : Map<String, String> = details.split(DETAILS).filter { ':' in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    }

    private fun resource(name : String) : List<List<String>> {
        val stream = Hover::class.java.getResourceAsStream("/hover/$name.tsv") ?: return emptyList()
        return stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
    }

    private fun table(name : String) : Map<String, List<Entry>> =
        resource(name).filter { it.size >= 6 }
            .groupBy({ it[0] }, { Entry(it[1], it[2], it[3], it[4], it[5], it.getOrNull(6) == "1", it.getOrNull(7).orEmpty()) })

    private val eventbTable by lazy { table("eventb") }
    private val classicalTable by lazy { table("classical") }

    /** Signatures and examples, by language and the title of the construct. */
    private class Extras(val signature : String?, val examples : List<String>)

    private val extras : Map<Pair<String, String>, Extras> by lazy {
        resource("examples").filter { it.size == 4 }.groupBy { it[0] to it[1] }.mapValues { (_, rows) ->
            Extras(
                rows.firstOrNull { it[2] == "sig" }?.get(3)?.replace("\\n", "\n"),
                rows.filter { it[2] != "sig" }.map { it[3].replace("\\n", "\n") },
            )
        }
    }

    // ----------------------------------------------------------------------
    // Keywords whose official documentation is prose

    private class Doc(val signature : String, val text : String, val example : String?, val where : Pair<String, String>)

    private fun handbook(page : String, section : String) = "$HANDBOOK$page" to "Rodin Handbook, $section"

    private val CONTEXT = handbook("context.html", "3.2.3 Contexts")
    private val MACHINE = handbook("machine.html", "3.2.4 Machines")
    private val EVENTS = handbook("machine.html#events", "3.2.4.4 Events")
    private val TERMINATION = handbook("machine.html#termination", "3.2.4.8 Termination")
    private val POS = handbook("generated_proof_obligations.html", "3.2.7 Generated proof obligations")
    private val GUARDS = handbook("machine.html#guards", "3.2.4.4.2 Guards")
    private val ACTIONS = handbook("machine.html#actions", "3.2.4.4.3 Actions")

    private val COUNTER = """
        machine Counter sees Limits
        variables n
        invariants
          @inv1 n ∈ 0 ‥ limit
        events
          event INITIALISATION
            then @act1 n ≔ 0
          end
          event inc
            when @grd1 n < limit
            then @act1 n ≔ n + 1
          end
        end
    """.trimIndent()

    private val ADD = """
        event add
          any k
          where
            @grd1 k ∈ 1 ‥ 3
            @grd2 n + k ≤ limit
          then
            @act1 n ≔ n + k
        end
    """.trimIndent()

    private val eventbKeywords : Map<String, Doc> = mapOf(
        "context" to Doc("context name\n  extends context …\n  sets S …\n  constants c …\n  axioms @label P …\nend",
            "The static part of a model: carrier sets, constants and axioms. A context can extend other contexts and is seen by machines.",
            "context Limits\nconstants limit\naxioms\n  @axm1 limit ∈ ℕ1\nend", CONTEXT),
        "extends" to Doc("context name extends context …\nevent name extends abstract_event",
            "In a context: the contexts whose sets, constants and axioms this context includes. In an event: the abstract event whose parameters, guards and actions this event inherits; it may add more.",
            "context Limits2 extends Limits\n\nevent add extends add\n  then @act2 total ≔ total + k\nend", handbook("context.html#extendind_a_context", "3.2.3.2 Extending a context")),
        "sets" to Doc("sets S …",
            "Carrier sets: new types, given by name. Each carrier set is non-empty. An enumerated set is declared as a carrier set, its elements as constants, and an axiom with `partition`.",
            "sets COLOUR\nconstants red green\naxioms\n  @axm1 partition(COLOUR, {red}, {green})", handbook("context.html#carrier_sets", "3.2.3.1 Carrier Sets")),
        "constants" to Doc("constants c …",
            "Constants of the context. Each constant needs an axiom from which its type can be inferred, such as `c ∈ S`.",
            "constants limit\naxioms\n  @axm1 limit ∈ ℕ1", handbook("context.html#constants_and_axioms", "3.2.3.3 Constants and axioms")),
        "axioms" to Doc("axioms\n  @label P\n  theorem @label Q",
            "Labelled predicates over the sets and constants that are assumed to hold. A theorem must be proven from the axioms before it (proof obligation THM).",
            "axioms\n  @axm1 limit ∈ ℕ1\n  theorem @thm1 limit > 0", handbook("context.html#constants_and_axioms", "3.2.3.3 Constants and axioms")),
        "theorem" to Doc("theorem @label P",
            "Marks an axiom, invariant or guard as a theorem: it must be proven from the predicates before it (proof obligation THM) and may then be used as a hypothesis.",
            "invariants\n  @inv1 n ∈ 0 ‥ 10\n  theorem @thm1 n ≤ 10", POS),
        "machine" to Doc("machine name refines abstract sees context …\n  variables v …\n  invariants @label P …\n  variant V\n  events …\nend",
            "The dynamic part of a model: variables, invariants and the events that change the variables. A machine can refine one machine and see contexts.",
            COUNTER, MACHINE),
        "refines" to Doc("machine name refines abstract_machine\nevent name refines abstract_event …",
            "In a machine: the abstract machine this machine refines; its invariants are kept. In an event: the abstract events this event refines; listing several merges them.",
            "machine Counter2 refines Counter sees Limits\n\nevent inc2 refines inc\n  when @grd1 n + 2 ≤ limit\n  then @act1 n ≔ n + 2\nend", handbook("machine.html#abstract_machine", "3.2.4.1 Refinement and Abstract machines")),
        "sees" to Doc("machine name sees context …",
            "The contexts whose sets and constants the machine can use; their axioms are hypotheses of the machine's proof obligations.",
            "machine Counter sees Limits", handbook("machine.html#seeing_a_context", "3.2.4.2 Seeing a context")),
        "variables" to Doc("variables v …",
            "The state of the machine. Each variable needs an invariant from which its type can be inferred; variables of the abstract machine may be kept.",
            "variables n\ninvariants\n  @inv1 n ∈ ℕ", handbook("machine.html#variables_and_invariants", "3.2.4.3 Variables and invariants")),
        "invariants" to Doc("invariants\n  @label P\n  theorem @label Q",
            "Labelled predicates that must hold in every state: the initialisation must establish them and every event preserve them (proof obligation INV).",
            "invariants\n  @inv1 n ∈ 0 ‥ limit", handbook("machine.html#variables_and_invariants", "3.2.4.3 Variables and invariants")),
        "variant" to Doc("variant V",
            "An integer or a finite set that every convergent event decreases and no anticipated event increases (proof obligations VAR, NAT, FIN).",
            "variant limit − n\n…\nconvergent event inc\n  when @grd1 n < limit\n  then @act1 n ≔ n + 1\nend", TERMINATION),
        "events" to Doc("events\n  event INITIALISATION … end\n  event name … end",
            "The events of the machine; INITIALISATION gives the variables their first values.",
            COUNTER, EVENTS),
        "event" to Doc("ordinary|convergent|anticipated event name refines|extends abstract …\n  any p …\n  where @label G …\n  with @label W …\n  then @label A …\nend",
            "A possible state change: when its guards hold for some parameters, its actions change the variables.",
            ADD, EVENTS),
        "INITIALISATION" to Doc("event INITIALISATION\n  then @label A …\nend",
            "The event that gives the variables their initial values. It has no parameters and no guards, and its actions must not read the variables.",
            "event INITIALISATION\n  then\n    @act1 n ≔ 0\n    @act2 log ≔ ∅\nend", handbook("machine.html#initialization", "3.2.4.4.5 Initialisation")),
        "ordinary" to Doc("ordinary event name", "The event may occur any number of times; the variant does not constrain it. This is the default.", null, TERMINATION),
        "convergent" to Doc("convergent event name",
            "The event must decrease the variant (proof obligation VAR), so it can only occur finitely often in a row.",
            "variant limit − n\n…\nconvergent event inc\n  when @grd1 n < limit\n  then @act1 n ≔ n + 1\nend", TERMINATION),
        "anticipated" to Doc("anticipated event name",
            "The event must not increase the variant; proving that it terminates is left to a refinement, which makes it convergent.",
            "anticipated event reset\n  then @act1 n ≔ 0\nend", TERMINATION),
        "any" to Doc("any p …\nwhere @label G …",
            "The parameters of the event. Their types must follow from the guards.",
            ADD, handbook("machine.html#parameters", "3.2.4.4.1 Parameters")),
        "where" to Doc("where @label G …",
            "The guards of the event: it can only occur when they hold. Guards may use constants, the machine's variables and the event's parameters.",
            ADD, GUARDS),
        "when" to Doc("when @label G …", "The guards of an event without parameters.", "event inc\n  when @grd1 n < limit\n  then @act1 n ≔ n + 1\nend", GUARDS),
        "with" to Doc("with @p W …\nwith @x' W …",
            "Witnesses: they give values to abstract parameters that the refining event drops (label `p`) and to disappearing abstract variables (label `x'`).",
            "event inc refines add\n  with @k k = 1\n  then @act1 n ≔ n + 1\nend", handbook("machine.html#witness", "3.2.4.4.4 Witnesses")),
        "then" to Doc("then @label A …",
            "The actions of the event. They are executed simultaneously; each variable may be assigned in one action only.",
            "then\n  @act1 n ≔ n + 1\n  @act2 log :∈ ℙ(ℕ)", ACTIONS),
        "begin" to Doc("begin @label A … end", "The actions of an event without parameters and guards.", "event reset\n  begin @act1 n ≔ 0\nend", ACTIONS),
        "end" to Doc("end", "Ends an event, machine or context.", null, MACHINE),
    )

    /** Classical B keywords that ProB's summary only mentions in prose. */
    private val classicalKeywords : Map<String, Doc> = run {
        val header = "$SYNTAX#Machine_header" to "ProB, Summary of B Syntax: Machine header"
        val sections = "$SYNTAX#Machine_sections" to "ProB, Summary of B Syntax: Machine sections"
        val inclusion = "$SYNTAX#Machine_inclusion" to "ProB, Summary of B Syntax: Machine inclusion"
        mapOf(
            "MODEL" to Doc("MODEL name", "A synonym for MACHINE.", null, header),
            "SYSTEM" to Doc("SYSTEM name", "A synonym for MACHINE.", null, header),
            "EVENTS" to Doc("EVENTS e;…", "A synonym for OPERATIONS.", null, header),
            "ref" to Doc("event ref abstract_event", "Atelier B's keyword for event refinement.", null, header),
            "ABSTRACT_CONSTANTS" to Doc("ABSTRACT_CONSTANTS c,…", "Constants that need not be implemented.", null, sections),
            "ABSTRACT_VARIABLES" to Doc("ABSTRACT_VARIABLES v,…", "Variables that need not be implemented.", null, sections),
            "VISIBLE_CONSTANTS" to Doc("VISIBLE_CONSTANTS c,…", "A synonym for CONCRETE_CONSTANTS.", null, sections),
            "VISIBLE_VARIABLES" to Doc("VISIBLE_VARIABLES v,…", "A synonym for CONCRETE_VARIABLES.", null, sections),
            "LOCAL_OPERATIONS" to Doc("LOCAL_OPERATIONS O;…", "Operations only available inside an implementation.", null, sections),
            "VALUES" to Doc("VALUES c = E;…", "Values of constants and deferred sets in an implementation; ProB supports it partially.", null, sections),
            "IMPORTS" to Doc("IMPORTS M,…", "Machines imported by an implementation.", "IMPLEMENTATION Counter_i\nREFINES Counter\nIMPORTS Storage", inclusion),
        )
    }

    // ----------------------------------------------------------------------
    // Entry point

    /**
     * The hover text for the position (0-based line and UTF-16 column) in [file], or null.
     * [text] is the file's content as the editor has it; [obligations] and [identifiers] come
     * from the file's last check.
     */
    fun at(file : File, text : String, line : Int, column : Int, obligations : List<ProofObligation>, identifiers : Map<String, String>) : String? {
        val source = text.lines().getOrNull(line) ?: return null
        return if (CamilleProject.isCamille(text)) eventb(file, source, line, column, obligations)
        else classical(source, column, identifiers)
    }

    // ----------------------------------------------------------------------
    // Layout

    /** A hover in the usual shape: signature, rule, documentation, example, link. */
    private fun card(language : String, signature : String?, body : List<String>, examples : List<String>, links : List<Pair<String, String>>) : String {
        val parts = mutableListOf<String>()
        signature?.let { parts += "```$language\n$it\n```" }
        val text = body.filter { it.isNotBlank() }
        if (signature != null && (text.isNotEmpty() || examples.isNotEmpty())) parts += "---"
        parts += text
        if (examples.isNotEmpty()) {
            parts += (if (examples.size == 1) "**Example**" else "**Examples**") + "\n```$language\n${examples.joinToString("\n")}\n```"
        }
        if (links.isNotEmpty()) parts += links.distinct().joinToString(" · ") { (url, title) -> "[$title]($url)" }
        return parts.joinToString("\n\n")
    }

    private fun render(doc : Doc, language : String) =
        card(language, doc.signature, listOf(doc.text), listOfNotNull(doc.example), listOf(doc.where))

    /** An Event-B operator, keeping the parts of the definition that concern its own symbol. */
    private fun renderOperator(entry : Entry) : String {
        val extra = extras["eventb" to entry.title]
        val symbol = entry.symbol
        fun relevant(field : String) : List<String> {
            val lines = entry.fields[field]?.split(" — ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (lines.isEmpty() || symbol.isEmpty()) return lines.take(3)
            if (field == "Definition") {
                // The definition of this very operator, not of its siblings in the same table.
                val own = lines.filter { line -> line.substringBefore("≙").contains(symbol) }
                return own.ifEmpty { lines.filter { symbol in it } }.ifEmpty { lines }.take(3)
            }
            // "for each operator □ of ∪, ∩, ∖: L(S □ T) ≙ …" becomes "L(S ∪ T) ≙ …".
            return lines.map { it.replace(Regex("""(?i)\s*for each operator □ of [^:]*:?\s*"""), " ").trim() }
                .filter { it.isNotEmpty() && (symbol in it || "□" in it) }
                .map { it.replace("□", symbol) }
                .ifEmpty { lines }.take(3)
        }
        val ascii = entry.fields["ASCII"]?.let { "Typed as $it." }
        val body = mutableListOf("**${entry.title.replaceFirstChar { it.uppercase() }}**" + (ascii?.let { " · $it" } ?: ""))
        if (entry.description.isNotEmpty()) body += entry.description.replace(" — ", "\n\n")
        val facts = listOf("Definition" to "Definition", "Types" to "Type", "WD" to "Well-definedness").mapNotNull { (field, name) ->
            relevant(field).takeIf { it.isNotEmpty() }?.let { "*$name:* " + it.joinToString("; ") }
        }
        if (facts.isNotEmpty()) body += facts.joinToString("  \n")
        return card("eventb", extra?.signature ?: symbol.ifEmpty { null }, body, extra?.examples.orEmpty(), listOf(entry.link to entry.source))
    }

    /** Entries of ProB's syntax summary; for a word like THEN, the constructs it belongs to. */
    private fun renderClassical(entries : List<Entry>) : String {
        val named = entries.filter { it.primary }.distinctBy { it.title + it.link }
        if (named.isNotEmpty()) {
            return named.take(3).joinToString("\n\n---\n\n") { entry ->
                val extra = extras["b" to entry.title]
                card("b", entry.title, listOf(entry.description.replaceFirstChar { it.uppercase() }), extra?.examples.orEmpty(), listOf(entry.link to entry.source))
            }
        }
        val parts = entries.distinctBy { it.title }.take(8)
        val signatures = parts.joinToString("\n") { it.title }
        val example = parts.firstNotNullOfOrNull { extras["b" to it.title]?.examples?.firstOrNull() }
        return card("b", signatures, listOf("Part of the constructs above."), listOfNotNull(example), parts.map { it.link to it.source })
    }

    private fun link(where : Pair<String, String>) = "[${where.second}](${where.first})"

    // ----------------------------------------------------------------------
    // Event-B

    private fun eventb(file : File, source : String, line : Int, column : Int, obligations : List<ProofObligation>) : String? {
        val word = wordAt(source, column)
        if (word == null) return operator(source, column, eventbTable)?.let { renderOperator(it.first()) }
        if (word.startsWith("@")) return label(file, word.removePrefix("@"), obligations)
        eventbKeywords[word]?.let { return render(it, "eventb") }
        val info = checked(file)
        if (info != null) {
            bound(info, word, line, column)?.let { return it }
            identifier(info, word, line, obligations)?.let { return it }
        }
        eventbTable[word]?.let { return renderOperator(it.first()) }
        return operator(source, column, eventbTable)?.let { renderOperator(it.first()) }
    }

    private fun checked(file : File) : Any? = CamilleProject(file).also { it.check() }.mainInfo

    private fun typed(name : String, type : org.eventb.core.ast.Type?) =
        if (type == null) name else "$name ∈ ${type.toExpression()}"

    private fun contextsOf(info : Any) : List<ContextInfo> = when (info) {
        is MachineInfo -> info.contexts
        is ContextInfo -> info.closure().toList()
        else -> emptyList()
    }

    private fun machinesOf(info : Any) : List<MachineInfo> =
        generateSequence(info as? MachineInfo) { it.abstract }.toList()

    /** Every labelled formula of the model, with the file it is written in. */
    private fun formulas(info : Any) : List<Pair<File, Labeled<*>>> {
        val result = mutableListOf<Pair<File, Labeled<*>>>()
        for (context in contextsOf(info)) context.axioms.forEach { result += context.component.file to it }
        for (machine in machinesOf(info)) {
            val file = machine.component.file
            machine.invariants.forEach { result += file to it }
            machine.variant?.let { result += file to it }
            for (event in machine.events.values) {
                (event.guards + event.actions + event.witnesses).forEach { result += file to it }
            }
        }
        return result
    }

    /** How a labelled formula reads in the source. */
    private fun show(labeled : Labeled<*>) =
        (if (labeled.theorem) "theorem " else "") + (if (labeled.label == "variant") "variant " else "@${labeled.label} ") +
            (labeled.source?.text?.trim()?.replace(Regex("""\s+"""), " ") ?: labeled.formula.toString())

    /** The formulas among [candidates] that mention [name], as they constrain or use it. */
    private fun mentions(name : String, candidates : List<Labeled<*>>) : List<String> =
        candidates.filter { l -> l.formula.freeIdentifiers.any { it.name == name } }.take(4).map { show(it) }

    private fun mentioning(title : String, lines : List<String>) =
        if (lines.isEmpty()) emptyList() else listOf("$title\n```eventb\n${lines.joinToString("\n")}\n```")

    /** A variable bound by a quantifier, comprehension, λ or an assignment's after-value, with its type. */
    private fun bound(info : Any, word : String, line : Int, column : Int) : String? {
        val main = (info as? MachineInfo)?.component?.file ?: (info as? ContextInfo)?.component?.file ?: return null
        for ((file, labeled) in formulas(info)) {
            val source = labeled.source ?: continue
            if (file != main) continue
            val offset = offsetIn(source, line, column) ?: continue
            val sub = try {
                labeled.formula.getPosition(SourceLocation(offset, offset))?.let { labeled.formula.getSubFormula(it) }
            } catch (e : RuntimeException) {
                null
            } ?: return null
            val type = when (sub) {
                is BoundIdentDecl -> sub.type
                is BoundIdentifier -> sub.type
                is FreeIdentifier -> if (sub.isPrimed) sub.type else return null
                else -> return null
            }
            if (word.endsWith("'")) {
                return card("eventb", "(after-value) ${typed(word, type)}",
                    listOf("The value of `${word.removeSuffix("'")}` after the event, in `@${labeled.label}`.", "```eventb\n${show(labeled)}\n```"),
                    emptyList(), listOf(handbook("assignments.html#nondeterministic_assignments", "3.3.8.2 Non-deterministic assignment with before-after-predicate")))
            }
            return card("eventb", "(bound variable) ${typed(word, type)}",
                listOf("Bound in `@${labeled.label}`:", "```eventb\n${show(labeled)}\n```"),
                emptyList(), listOf(handbook("predicates.html#quantified_predicates", "3.3.2.3 Quantified predicates")))
        }
        return null
    }

    /** The labelled formula of the main component written on [line]. */
    private fun formulaAt(info : Any, line : Int) : Labeled<*>? {
        val main = (info as? MachineInfo)?.component?.file ?: (info as? ContextInfo)?.component?.file ?: return null
        return formulas(info).firstOrNull { (file, labeled) ->
            val source = labeled.source
            file == main && source != null && line in (source.line - 1)..(source.line - 1 + source.text.count { it == '\n' })
        }?.second
    }

    /** The offset of (line, column) in the text of [formula], if it lies inside. */
    private fun offsetIn(formula : TFormula, line : Int, column : Int) : Int? {
        var l = formula.line - 1
        var c = formula.pos - 1
        for ((index, char) in formula.text.withIndex()) {
            if (l == line && c == column) return index
            if (char == '\n') {
                l++
                c = 0
            } else {
                c++
            }
        }
        return null
    }

    /** How an event reads in the source, compactly. */
    private fun outline(event : EventInfo) : String {
        val lines = mutableListOf<String>()
        val convergence = if (event.convergence == Convergence.ORDINARY) "" else event.convergence.name.lowercase() + " "
        val refines = when {
            event.refines.isEmpty() -> ""
            event.extended -> " extends " + event.refines.first().name
            else -> " refines " + event.refines.joinToString(" ") { it.name }
        }
        lines += "${convergence}event ${event.name}$refines"
        if (event.parameters.isNotEmpty()) lines += "  any " + event.parameters.keys.joinToString(" ")
        if (event.guards.isNotEmpty()) lines += "  where " + event.guards.joinToString("\n        ") { show(it) }
        if (event.witnesses.isNotEmpty()) lines += "  with " + event.witnesses.joinToString("\n       ") { show(it) }
        if (event.actions.isNotEmpty()) lines += "  then " + event.actions.joinToString("\n       ") { show(it) }
        lines += "end"
        return lines.joinToString("\n")
    }

    private fun identifier(info : Any, word : String, line : Int, obligations : List<ProofObligation>) : String? {
        val name = word.removeSuffix("'")
        val primed = word.endsWith("'")
        val contexts = contextsOf(info)
        val machines = machinesOf(info)
        val axioms = contexts.flatMap { it.axioms }
        val invariants = machines.flatMap { it.invariants }

        for (context in contexts.reversed()) {
            if (name in context.sets) {
                return card("eventb", "(carrier set) $name",
                    listOf("A carrier set of context `${context.name}`: a new, non-empty type.") + mentioning("Axioms about it:", mentions(name, axioms)),
                    emptyList(), listOf(handbook("context.html#carrier_sets", "3.2.3.1 Carrier Sets")))
            }
            if (name in context.constants) {
                val type = if (context.env.contains(name)) context.env.getType(name) else null
                return card("eventb", "(constant) ${typed(name, type)}",
                    listOf("A constant of context `${context.name}`.") + mentioning("Axioms about it:", mentions(name, axioms)),
                    emptyList(), listOf(handbook("context.html#constants_and_axioms", "3.2.3.3 Constants and axioms")))
            }
        }

        for (machine in machines) {
            if (name in machine.variables) {
                val env = (info as MachineInfo).env
                val type = if (env.contains(name)) env.getType(name) else null
                val kept = if (machine !== info) " of the abstract machine" else ""
                val signature = if (primed) "(after-value) ${typed(word, type)}" else "(variable$kept) ${typed(name, type)}"
                val changing = info.events.values.filter { name in it.assigned }.map { it.name }
                val here = if (primed) formulaAt(info, line)?.let { listOf("```eventb\n${show(it)}\n```") }.orEmpty() else emptyList()
                val text = listOf(
                    if (primed) "The value of `$name` after the event." else "A variable of machine `${machine.name}`.",
                ) + here + mentioning("Invariants about it:", mentions(name, invariants)) +
                    (if (changing.isEmpty()) emptyList() else listOf("Changed by: " + changing.joinToString(", ") { "`$it`" }))
                return card("eventb", signature, text, emptyList(), listOf(handbook("machine.html#variables_and_invariants", "3.2.4.3 Variables and invariants")))
            }
        }

        // Components named in sees, refines and extends.
        contexts.firstOrNull { it.name == name }?.let { context ->
            val signature = "context ${context.name}" + (if (context.extended.isEmpty()) "" else " extends " + context.extended.joinToString(" ") { it.name }) +
                (if (context.sets.isEmpty()) "" else "\n  sets " + context.sets.joinToString(" ")) +
                (if (context.constants.isEmpty()) "" else "\n  constants " + context.constants.joinToString(" ")) +
                (if (context.axioms.isEmpty()) "" else "\n  axioms\n    " + context.axioms.joinToString("\n    ") { show(it) }) + "\nend"
            return card("eventb", signature, listOf("In `${context.component.file.name}`."), emptyList(), listOf(CONTEXT))
        }
        machines.firstOrNull { it.name == name }?.let { machine ->
            val signature = "machine ${machine.name}" + (machine.abstract?.let { " refines ${it.name}" } ?: "") +
                (if (machine.variables.isEmpty()) "" else "\n  variables " + machine.variables.joinToString(" ")) +
                (if (machine.invariants.isEmpty()) "" else "\n  invariants\n    " + machine.invariants.joinToString("\n    ") { show(it) }) +
                "\n  events " + machine.events.keys.joinToString(" ") + "\nend"
            return card("eventb", signature, listOf("In `${machine.component.file.name}`."), emptyList(), listOf(MACHINE))
        }

        // Events of the machine and of its abstractions.
        for (machine in machines) {
            val event = machine.events[name] ?: continue
            val own = machine === info
            val text = mutableListOf<String>()
            if (!own) text += "An event of the abstract machine `${machine.name}`."
            if (event.parameters.isNotEmpty()) {
                text += "Parameters: " + event.parameters.entries.joinToString(", ") { (p, type) -> "`$p ∈ ${type.toExpression()}`" }
            }
            if (own) text += statuses(obligations.filter { it.name.startsWith("$name/") })
            return card("eventb", outline(event), text, emptyList(), listOf(EVENTS))
        }

        // A parameter of the event the line belongs to, or of an abstract event it refines.
        val machine = info as? MachineInfo ?: return null
        val event = machine.events.values.filter { it.token.line - 1 <= line }.maxByOrNull { it.token.line } ?: return null
        (event.parameters[name] ?: event.refines.firstNotNullOfOrNull { it.parameters[name] })?.let { type ->
            val abstract = name !in event.parameters
            val text = listOf(
                if (abstract) "A parameter of the abstract event, given a value by a witness." else "A parameter of event `${event.name}`.",
            ) + mentioning("Guards about it:", mentions(name, event.guards + event.witnesses))
            return card("eventb", "(parameter) ${typed(name, type)}", text, emptyList(), listOf(handbook("machine.html#parameters", "3.2.4.4.1 Parameters")))
        }
        return null
    }

    private fun label(file : File, name : String, obligations : List<ProofObligation>) : String? {
        val info = checked(file) ?: return null
        fun describe(kind : String, labeled : Labeled<*>, where : Pair<String, String>, related : List<ProofObligation>) =
            card("eventb", show(labeled), listOf("`@$name` is ${if (labeled.theorem) "a theorem, " else ""}an $kind.".replace("an guard", "a guard").replace("an witness", "a witness"), statuses(related)), emptyList(), listOf(where))

        val mine = obligations.filter { it.name.startsWith("$name/") || it.name.contains("/$name/") }
        for (machine in machinesOf(info)) {
            val own = machine === info
            machine.invariants.firstOrNull { it.label == name }?.let {
                return describe("invariant of `${machine.name}`", it, handbook("machine.html#variables_and_invariants", "3.2.4.3 Variables and invariants"), if (own) mine else emptyList())
            }
            for (event in machine.events.values) {
                val related = if (own) obligations.filter { it.name.startsWith("${event.name}/$name/") } else emptyList()
                event.guards.firstOrNull { it.label == name }?.let { return describe("guard of `${event.name}`", it, GUARDS, related) }
                event.actions.firstOrNull { it.label == name }?.let { return describe("action of `${event.name}`", it, ACTIONS, related) }
                event.witnesses.firstOrNull { it.label == name }?.let { return describe("witness of `${event.name}`", it, handbook("machine.html#witness", "3.2.4.4.4 Witnesses"), related) }
            }
        }
        for (context in contextsOf(info)) {
            context.axioms.firstOrNull { it.label == name }?.let {
                return describe("axiom of `${context.name}`", it, handbook("context.html#constants_and_axioms", "3.2.3.3 Constants and axioms"), if (context === info) mine else emptyList())
            }
        }
        return null
    }

    /** The proof obligations and what ProB made of them, as Rodin lists them per element. */
    private fun statuses(obligations : List<ProofObligation>) : String {
        if (obligations.isEmpty()) return ""
        val lines = obligations.joinToString("\n") { po ->
            val mark = when (po.status) {
                ProofObligation.Status.DISCHARGED -> "✓"
                ProofObligation.Status.DISPROVED -> "✗"
                ProofObligation.Status.UNDISCHARGED -> "?"
            }
            "- $mark `${po.name}`" + when (po.status) {
                ProofObligation.Status.DISPROVED -> " does not hold" + (if (po.detail.isEmpty()) "" else ", e.g. for `${po.detail}`")
                ProofObligation.Status.UNDISCHARGED -> " not discharged by ProB"
                ProofObligation.Status.DISCHARGED -> ""
            }
        }
        return "**Proof obligations** ([3.2.7](${POS.first}))\n$lines"
    }

    // ----------------------------------------------------------------------
    // Classical B

    private fun classical(source : String, column : Int, identifiers : Map<String, String>) : String? {
        val word = wordAt(source, column)
        if (word != null && !word.startsWith("@")) {
            identifiers[word]?.let { return it }
            classicalTable[word]?.let { return renderClassical(it) }
            classicalKeywords[word]?.let { return render(it, "b") }
        }
        operator(source, column, classicalTable)?.let { return renderClassical(it) }
        // ProB also reads the Unicode symbols of Atelier B.
        val char = source.getOrNull(column) ?: return null
        val ascii = UnicodeTranslator.toAscii(char.toString()).trim()
        return classicalTable[ascii]?.let { renderClassical(it) }
    }

    // ----------------------------------------------------------------------
    // Finding what is under the cursor

    private fun isWordChar(c : Char) = c.isLetterOrDigit() || c == '_' || c == '\''

    private fun isSymbolLetter(c : Char) = c in "ℕℤℙλ"

    private fun wordAt(source : String, column : Int) : String? {
        if (column !in source.indices) return null
        val char = source[column]
        if (!(isWordChar(char) || char == '@') || isSymbolLetter(char)) return null
        var start = column
        while (start > 0 && (isWordChar(source[start - 1]) || source[start - 1] == '@') && !isSymbolLetter(source[start - 1])) start--
        var end = column
        while (end < source.length && isWordChar(source[end]) && !isSymbolLetter(source[end])) end++
        return source.substring(start, end).takeIf { it.isNotBlank() && it != "'" }
    }

    /** The longest operator of [table] that covers the column. */
    private fun operator(source : String, column : Int, table : Map<String, List<Entry>>) : List<Entry>? {
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
}
