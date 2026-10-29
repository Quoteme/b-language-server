package b.language.server.proBMangement.prob

import org.eventb.core.ast.Expression
import org.eventb.core.ast.Formula
import org.eventb.core.ast.FreeIdentifier
import org.eventb.core.ast.ISealedTypeEnvironment
import org.eventb.core.ast.ITypeEnvironment
import org.eventb.core.ast.Predicate

/*
 * Interactive proofs as in Rodin's sequent prover (org.eventb.core.seqprover): a proof is a tree of
 * rules, each turning a sequent into the sequents of its antecedents. Rules are kept in Rodin's
 * form (reasoner, goal, needed hypotheses, antecedents), so that they are written to and read from
 * Rodin's .bpr files, and are replayed structurally, as Rodin reuses proofs.
 */

/** Rodin's confidence levels (IConfidence). */
object Confidence {
    const val PENDING = 0
    const val REVIEWED_MAX = 500
    const val DISCHARGED_MAX = 1000
}

enum class HypState { SELECTED, VISIBLE, HIDDEN }

/** A sequent: hypotheses, each selected, visible or hidden, and a goal. */
class Sequent(val env: ISealedTypeEnvironment, val hyps: Map<Predicate, HypState>, val goal: Predicate) {

    fun contains(predicate: Predicate) = predicate in hyps
    fun containsAll(predicates: Collection<Predicate>) = predicates.all { it in hyps }
    fun state(predicate: Predicate) = hyps[predicate]
    val factory get() = goal.factory

    companion object {
        fun of(po: ProofObligation): Sequent {
            val hyps = LinkedHashMap<Predicate, HypState>()
            po.hypotheses.forEach { hyps[it.predicate] = if (it.selected) HypState.SELECTED else HypState.VISIBLE }
            return Sequent(po.env.makeSnapshot(), hyps, po.goal)
        }
    }
}

/** A change to the hypotheses of an antecedent, as Rodin's IHypAction. */
sealed class HypAction(val type: String) {
    /** SELECT, DESELECT, HIDE or SHOW [hyps]. */
    class Selection(type: String, val hyps: List<Predicate>) : HypAction(type)

    /** FORWARD_INF: [inferred] follows from [hyps]; REWRITE: [inferred] replaces [hidden]. */
    class Inference(type: String, val hyps: List<Predicate>, val idents: List<FreeIdentifier>,
                    val inferred: List<Predicate>, val hidden: List<Predicate> = emptyList()) : HypAction(type)

    companion object {
        const val SELECT = "SELECT"
        const val DESELECT = "DESELECT"
        const val HIDE = "HIDE"
        const val SHOW = "SHOW"
        const val FORWARD_INF = "FORWARD_INF"
        const val REWRITE = "REWRITE"
    }
}

/** A sequent the rule produces: a new goal (or the old one), hypotheses and identifiers added. */
class Antecedent(
    val goal: Predicate?,
    val added: List<Predicate> = emptyList(),
    /** Added hypotheses (also in [added]) that are not selected. */
    val unselected: List<Predicate> = emptyList(),
    val idents: List<FreeIdentifier> = emptyList(),
    val actions: List<HypAction> = emptyList(),
)

/** The input a reasoner stores with its rule, under a key, as Rodin's IReasonerInputWriter. */
sealed class RuleInput(val key: String) {
    class Text(key: String, val value: String) : RuleInput(key)
    class Expressions(key: String, val values: List<Expression?>) : RuleInput(key)
    class Predicates(key: String, val values: List<Predicate>) : RuleInput(key)
}

/**
 * A rule of a proof, as Rodin's IProofRule. [reasoner] is the reasoner's id with its version,
 * as Rodin writes it (org.eventb.core.seqprover.eq:1).
 */
class Rule(
    val reasoner: String,
    val display: String,
    val goal: Predicate?,
    val needed: Set<Predicate>,
    val antecedents: List<Antecedent>,
    val confidence: Int = Confidence.DISCHARGED_MAX,
    val inputs: List<RuleInput> = emptyList(),
) {
    /** The antecedents' sequents, or null if the rule does not apply to [sequent]. */
    fun apply(sequent: Sequent): List<Sequent>? {
        if (!sequent.containsAll(needed)) return null
        if (goal != null && goal != sequent.goal) return null
        val goalInstantiation = if (goal == null) sequent.goal else null
        return antecedents.map { generate(sequent, it, goalInstantiation) ?: return null }
    }

    private fun generate(sequent: Sequent, antecedent: Antecedent, goalInstantiation: Predicate?): Sequent? {
        val newGoal = antecedent.goal ?: goalInstantiation ?: return null
        val env = withIdents(sequent.env, antecedent.idents) ?: return null
        if (!typed(env, antecedent.added + newGoal)) return null
        if (!antecedent.added.containsAll(antecedent.unselected)) return null
        val hyps = LinkedHashMap(sequent.hyps)
        for (hyp in antecedent.added) {
            hyps[hyp] = if (hyp in antecedent.unselected) {
                if (hyps[hyp] == HypState.SELECTED) HypState.SELECTED else HypState.VISIBLE
            } else HypState.SELECTED
        }
        var result = Sequent(env, hyps, newGoal)
        for (action in antecedent.actions) result = perform(result, action)
        return result
    }

    companion object {
        /** [env] with the fresh identifiers [idents], or null if one is not fresh. */
        fun withIdents(env : ISealedTypeEnvironment, idents : List<FreeIdentifier>) : ISealedTypeEnvironment? {
            if (idents.isEmpty()) return env
            val builder = env.makeBuilder()
            for (ident in idents) {
                if (builder.contains(ident.name)) return null
                builder.add(ident)
            }
            return builder.makeSnapshot()
        }

        /** Whether every free identifier of [predicates] is in [env] with its type. */
        fun typed(env : ITypeEnvironment, predicates : Collection<Predicate>) : Boolean =
            predicates.all { p -> p.isTypeChecked && p.freeIdentifiers.all { env.getType(it.name) == it.type } }

        fun perform(sequent : Sequent, action : HypAction) : Sequent {
            val hyps = LinkedHashMap(sequent.hyps)
            when (action) {
                is HypAction.Selection -> {
                    for (hyp in action.hyps) {
                        val state = hyps[hyp] ?: continue
                        hyps[hyp] = when (action.type) {
                            HypAction.SELECT -> if (state == HypState.HIDDEN) state else HypState.SELECTED
                            HypAction.DESELECT -> if (state == HypState.SELECTED) HypState.VISIBLE else state
                            HypAction.HIDE -> HypState.HIDDEN
                            HypAction.SHOW -> if (state == HypState.HIDDEN) HypState.VISIBLE else state
                            else -> state
                        }
                    }
                    return Sequent(sequent.env, hyps, sequent.goal)
                }
                is HypAction.Inference -> {
                    // Skipped when the hypotheses are gone, as in Rodin.
                    if (!sequent.containsAll(action.hyps)) return sequent
                    val env = withIdents(sequent.env, action.idents) ?: return sequent
                    if (!typed(env, action.inferred)) return sequent
                    val select = action.hyps.isEmpty() || action.hyps.any { hyps[it] == HypState.SELECTED }
                    val hide = !select && action.hyps.all { hyps[it] == HypState.HIDDEN }
                    for (inferred in action.inferred) {
                        val state = hyps[inferred]
                        if (state == null) {
                            hyps[inferred] = if (select) HypState.SELECTED else if (hide) HypState.HIDDEN else HypState.VISIBLE
                        } else if (select && state != HypState.HIDDEN) {
                            hyps[inferred] = HypState.SELECTED
                        }
                    }
                    for (hidden in action.hidden) if (hidden in hyps) hyps[hidden] = HypState.HIDDEN
                    return Sequent(env, hyps, sequent.goal)
                }
            }
        }
    }
}

/** A node of a proof tree: open while it has no rule. */
class ProofNode(val sequent: Sequent, val parent: ProofNode? = null) {
    var rule: Rule? = null
        private set
    var children: List<ProofNode> = emptyList()
        private set
    /** The saved proof from this node, whose first rule no longer applies. */
    var broken: Skeleton? = null

    val isOpen get() = rule == null

    /** Rodin's confidence: pending if a leaf is open, reviewed if a step is reviewed. */
    val confidence: Int get() = rule?.let { r -> (children.map { it.confidence } + r.confidence).min() } ?: Confidence.PENDING

    val isClosed get() = confidence > Confidence.PENDING

    /** Applies [rule] here, replacing the subtree; false if it does not apply. */
    fun apply(rule: Rule): Boolean {
        val sequents = rule.apply(sequent) ?: return false
        this.rule = rule
        broken = null
        children = sequents.map { ProofNode(it, this) }
        return true
    }

    fun prune() {
        rule = null
        broken = null
        children = emptyList()
    }

    /** The node's place in the tree: the indices of the antecedents leading to it, as 0.1.0. */
    val path: String get() = parent?.let { p -> (p.path.takeIf { it.isNotEmpty() }?.plus(".") ?: "") + p.children.indexOf(this) } ?: ""

    fun find(path: String): ProofNode? {
        if (path.isEmpty()) return this
        var node: ProofNode = this
        for (index in path.split(".")) node = node.children.getOrNull(index.toIntOrNull() ?: return null) ?: return null
        return node
    }

    fun nodes(): List<ProofNode> = listOf(this) + children.flatMap { it.nodes() }

    fun openLeaves(): List<ProofNode> = nodes().filter { it.isOpen }

    /** The rules from here; a broken proof is kept as it was saved, so saving does not lose it. */
    fun skeleton(): Skeleton = if (rule == null) broken ?: Skeleton(null, emptyList()) else Skeleton(rule, children.map { it.skeleton() })

    /**
     * Replays [skeleton] from here, as Rodin reuses a proof: each saved rule is applied to the
     * sequent it now meets. Where a rule no longer applies, the node is left open and the saved
     * proof from there is kept as [broken].
     * @return whether every rule applied
     */
    fun replay(skeleton: Skeleton): Boolean {
        val saved = skeleton.rule ?: return true
        if (!apply(saved)) {
            prune()
            broken = skeleton
            return false
        }
        var complete = true
        children.zip(skeleton.children).forEach { (child, s) -> complete = child.replay(s) && complete }
        return complete
    }
}

/** The rules of a proof tree, without its sequents: what Rodin calls a proof skeleton. */
class Skeleton(val rule: Rule?, val children: List<Skeleton>)

/** A proof of an obligation: its tree, with undo and redo. */
class ProofTree(val po: ProofObligation) {
    var root = ProofNode(Sequent.of(po))
        private set
    private val undo = ArrayDeque<Skeleton>()
    private val redo = ArrayDeque<Skeleton>()

    val confidence get() = root.confidence
    val complete get() = root.isClosed

    /** Records the tree before a change, for [undo]. */
    fun remember() {
        undo.addLast(root.skeleton())
        if (undo.size > 200) undo.removeFirst()
        redo.clear()
    }

    fun undo(): Boolean = restore(undo, redo)
    fun redo(): Boolean = restore(redo, undo)

    private fun restore(from: ArrayDeque<Skeleton>, to: ArrayDeque<Skeleton>): Boolean {
        val skeleton = from.removeLastOrNull() ?: return false
        to.addLast(root.skeleton())
        load(skeleton)
        return true
    }

    /** Rebuilds the tree from [skeleton]; whether every rule applied. */
    fun load(skeleton: Skeleton): Boolean {
        root = ProofNode(Sequent.of(po))
        return root.replay(skeleton)
    }

    /**
     * What Rodin stores as the proof's dependencies: the hypotheses of the obligation and the
     * identifiers the rules use, and those they introduce.
     */
    fun dependencies(): Dependencies {
        val used = LinkedHashSet<Predicate>()
        val introduced = LinkedHashSet<String>()
        var goalUsed = false
        fun visit(node: ProofNode, goalUnchanged: Boolean) {
            val rule = node.rule ?: return
            if (rule.goal != null && goalUnchanged) goalUsed = true
            rule.needed.filter { it in po.hypotheses.map { h -> h.predicate } }.forEach { used += it }
            rule.antecedents.forEach { a -> a.idents.forEach { introduced += it.name } }
            node.children.zip(rule.antecedents).forEach { (child, a) -> visit(child, goalUnchanged && a.goal == null) }
        }
        visit(root, true)
        return Dependencies(if (goalUsed) po.goal else null, used, introduced)
    }

    class Dependencies(val goal: Predicate?, val hyps: Set<Predicate>, val introduced: Set<String>)
}

/** Every position of [formula], with its sub-formula, in pre-order. */
fun positionsOf(formula: Formula<*>): List<Pair<org.eventb.core.ast.IPosition, Formula<*>>> {
    val result = mutableListOf<Pair<org.eventb.core.ast.IPosition, Formula<*>>>()
    fun walk(position: org.eventb.core.ast.IPosition, sub: Formula<*>) {
        result += position to sub
        for (i in 0 until sub.childCount) walk(position.getChildAtIndex(i), sub.getChild(i))
    }
    walk(org.eventb.core.ast.IPosition.ROOT, formula)
    return result
}
