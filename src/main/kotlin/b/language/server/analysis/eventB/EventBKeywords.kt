package b.language.server.analysis.eventB

import b.language.server.analysis.documentation.KeywordDocumentation
import b.language.server.analysis.documentation.Link

/** The keywords of Event-B's structure, whose official documentation is prose. */
object EventBKeywords {
    private val COUNTER =
        """
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

    private val ADD =
        """
        event add
          any k
          where
            @grd1 k ∈ 1 ‥ 3
            @grd2 n + k ≤ limit
          then
            @act1 n ≔ n + k
        end
        """.trimIndent()

    private val all: Map<String, KeywordDocumentation> =
        mapOf(
            "context" to
                KeywordDocumentation(
                    "context name\n  extends context …\n  sets S …\n  constants c …\n  axioms @label P …\nend",
                    "The static part of a model: carrier sets, constants and axioms. A context can extend other contexts and is seen by machines.",
                    "context Limits\nconstants limit\naxioms\n  @axm1 limit ∈ ℕ1\nend",
                    RodinHandbook.CONTEXT,
                ),
            "extends" to
                KeywordDocumentation(
                    "context name extends context …\nevent name extends abstract_event",
                    "In a context: the contexts whose sets, constants and axioms this context includes. In an event: the abstract event whose parameters, guards and actions this event inherits; it may add more.",
                    "context Limits2 extends Limits\n\nevent add extends add\n  then @act2 total ≔ total + k\nend",
                    Link.handbook("context.html#extendind_a_context", "3.2.3.2 Extending a context"),
                ),
            "sets" to
                KeywordDocumentation(
                    "sets S …",
                    "Carrier sets: new types, given by name. Each carrier set is non-empty. An enumerated set is declared as a carrier set, its elements as constants, and an axiom with `partition`.",
                    "sets COLOUR\nconstants red green\naxioms\n  @axm1 partition(COLOUR, {red}, {green})",
                    Link.handbook("context.html#carrier_sets", "3.2.3.1 Carrier Sets"),
                ),
            "constants" to
                KeywordDocumentation(
                    "constants c …",
                    "Constants of the context. Each constant needs an axiom from which its type can be inferred, such as `c ∈ S`.",
                    "constants limit\naxioms\n  @axm1 limit ∈ ℕ1",
                    Link.handbook("context.html#constants_and_axioms", "3.2.3.3 Constants and axioms"),
                ),
            "axioms" to
                KeywordDocumentation(
                    "axioms\n  @label P\n  theorem @label Q",
                    "Labelled predicates over the sets and constants that are assumed to hold. A theorem must be proven from the axioms before it (proof obligation THM).",
                    "axioms\n  @axm1 limit ∈ ℕ1\n  theorem @thm1 limit > 0",
                    Link.handbook("context.html#constants_and_axioms", "3.2.3.3 Constants and axioms"),
                ),
            "theorem" to
                KeywordDocumentation(
                    "theorem @label P",
                    "Marks an axiom, invariant or guard as a theorem: it must be proven from the predicates before it (proof obligation THM) and may then be used as a hypothesis.",
                    "invariants\n  @inv1 n ∈ 0 ‥ 10\n  theorem @thm1 n ≤ 10",
                    RodinHandbook.POS,
                ),
            "machine" to
                KeywordDocumentation(
                    "machine name refines abstract sees context …\n  variables v …\n  invariants @label P …\n  variant V\n  events …\nend",
                    "The dynamic part of a model: variables, invariants and the events that change the variables. A machine can refine one machine and see contexts.",
                    COUNTER,
                    RodinHandbook.MACHINE,
                ),
            "refines" to
                KeywordDocumentation(
                    "machine name refines abstract_machine\nevent name refines abstract_event …",
                    "In a machine: the abstract machine this machine refines; its invariants are kept. In an event: the abstract events this event refines; listing several merges them.",
                    "machine Counter2 refines Counter sees Limits\n\nevent inc2 refines inc\n  when @grd1 n + 2 ≤ limit\n  then @act1 n ≔ n + 2\nend",
                    Link.handbook("machine.html#abstract_machine", "3.2.4.1 Refinement and Abstract machines"),
                ),
            "sees" to
                KeywordDocumentation(
                    "machine name sees context …",
                    "The contexts whose sets and constants the machine can use; their axioms are hypotheses of the machine's proof obligations.",
                    "machine Counter sees Limits",
                    Link.handbook("machine.html#seeing_a_context", "3.2.4.2 Seeing a context"),
                ),
            "variables" to
                KeywordDocumentation(
                    "variables v …",
                    "The state of the machine. Each variable needs an invariant from which its type can be inferred; variables of the abstract machine may be kept.",
                    "variables n\ninvariants\n  @inv1 n ∈ ℕ",
                    Link.handbook("machine.html#variables_and_invariants", "3.2.4.3 Variables and invariants"),
                ),
            "invariants" to
                KeywordDocumentation(
                    "invariants\n  @label P\n  theorem @label Q",
                    "Labelled predicates that must hold in every state: the initialisation must establish them and every event preserve them (proof obligation INV).",
                    "invariants\n  @inv1 n ∈ 0 ‥ limit",
                    Link.handbook("machine.html#variables_and_invariants", "3.2.4.3 Variables and invariants"),
                ),
            "variant" to
                KeywordDocumentation(
                    "variant V",
                    "An integer or a finite set that every convergent event decreases and no anticipated event increases (proof obligations VAR, NAT, FIN).",
                    "variant limit − n\n…\nconvergent event inc\n  when @grd1 n < limit\n  then @act1 n ≔ n + 1\nend",
                    RodinHandbook.TERMINATION,
                ),
            "events" to
                KeywordDocumentation(
                    "events\n  event INITIALISATION … end\n  event name … end",
                    "The events of the machine; INITIALISATION gives the variables their first values.",
                    COUNTER,
                    RodinHandbook.EVENTS,
                ),
            "event" to
                KeywordDocumentation(
                    "ordinary|convergent|anticipated event name refines|extends abstract …\n  any p …\n  where @label G …\n  with @label W …\n  then @label A …\nend",
                    "A possible state change: when its guards hold for some parameters, its actions change the variables.",
                    ADD,
                    RodinHandbook.EVENTS,
                ),
            "INITIALISATION" to
                KeywordDocumentation(
                    "event INITIALISATION\n  then @label A …\nend",
                    "The event that gives the variables their initial values. It has no parameters and no guards, and its actions must not read the variables.",
                    "event INITIALISATION\n  then\n    @act1 n ≔ 0\n    @act2 log ≔ ∅\nend",
                    Link.handbook("machine.html#initialization", "3.2.4.4.5 Initialisation"),
                ),
            "ordinary" to
                KeywordDocumentation(
                    "ordinary event name",
                    "The event may occur any number of times; the variant does not constrain it. This is the default.",
                    null,
                    RodinHandbook.TERMINATION,
                ),
            "convergent" to
                KeywordDocumentation(
                    "convergent event name",
                    "The event must decrease the variant (proof obligation VAR), so it can only occur finitely often in a row.",
                    "variant limit − n\n…\nconvergent event inc\n  when @grd1 n < limit\n  then @act1 n ≔ n + 1\nend",
                    RodinHandbook.TERMINATION,
                ),
            "anticipated" to
                KeywordDocumentation(
                    "anticipated event name",
                    "The event must not increase the variant; proving that it terminates is left to a refinement, which makes it convergent.",
                    "anticipated event reset\n  then @act1 n ≔ 0\nend",
                    RodinHandbook.TERMINATION,
                ),
            "any" to
                KeywordDocumentation(
                    "any p …\nwhere @label G …",
                    "The parameters of the event. Their types must follow from the guards.",
                    ADD,
                    Link.handbook("machine.html#parameters", "3.2.4.4.1 Parameters"),
                ),
            "where" to
                KeywordDocumentation(
                    "where @label G …",
                    "The guards of the event: it can only occur when they hold. Guards may use constants, the machine's variables and the event's parameters.",
                    ADD,
                    RodinHandbook.GUARDS,
                ),
            "when" to
                KeywordDocumentation(
                    "when @label G …",
                    "The guards of an event without parameters.",
                    "event inc\n  when @grd1 n < limit\n  then @act1 n ≔ n + 1\nend",
                    RodinHandbook.GUARDS,
                ),
            "with" to
                KeywordDocumentation(
                    "with @p W …\nwith @x' W …",
                    "Witnesses: they give values to abstract parameters that the refining event drops (label `p`) and to disappearing abstract variables (label `x'`).",
                    "event inc refines add\n  with @k k = 1\n  then @act1 n ≔ n + 1\nend",
                    Link.handbook("machine.html#witness", "3.2.4.4.4 Witnesses"),
                ),
            "then" to
                KeywordDocumentation(
                    "then @label A …",
                    "The actions of the event. They are executed simultaneously; each variable may be assigned in one action only.",
                    "then\n  @act1 n ≔ n + 1\n  @act2 log :∈ ℙ(ℕ)",
                    RodinHandbook.ACTIONS,
                ),
            "begin" to
                KeywordDocumentation(
                    "begin @label A … end",
                    "The actions of an event without parameters and guards.",
                    "event reset\n  begin @act1 n ≔ 0\nend",
                    RodinHandbook.ACTIONS,
                ),
            "end" to KeywordDocumentation("end", "Ends an event, machine or context.", null, RodinHandbook.MACHINE),
        )

    operator fun get(word: String): KeywordDocumentation? = all[word]

    val entries: Map<String, KeywordDocumentation> get() = all
}

/** Sections of the Rodin Handbook that documentation links to. */
object RodinHandbook {
    val CONTEXT = Link.handbook("context.html", "3.2.3 Contexts")
    val MACHINE = Link.handbook("machine.html", "3.2.4 Machines")
    val EVENTS = Link.handbook("machine.html#events", "3.2.4.4 Events")
    val TERMINATION = Link.handbook("machine.html#termination", "3.2.4.8 Termination")
    val POS = Link.handbook("generated_proof_obligations.html", "3.2.7 Generated proof obligations")
    val GUARDS = Link.handbook("machine.html#guards", "3.2.4.4.2 Guards")
    val ACTIONS = Link.handbook("machine.html#actions", "3.2.4.4.3 Actions")
}
