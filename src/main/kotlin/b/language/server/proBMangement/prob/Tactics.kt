package b.language.server.proBMangement.prob

import org.eventb.core.ast.AssociativeExpression
import org.eventb.core.ast.AssociativePredicate
import org.eventb.core.ast.BinaryExpression
import org.eventb.core.ast.BinaryPredicate
import org.eventb.core.ast.BoundIdentDecl
import org.eventb.core.ast.Expression
import org.eventb.core.ast.Formula
import org.eventb.core.ast.FormulaFactory
import org.eventb.core.ast.IPosition
import org.eventb.core.ast.PowerSetType
import org.eventb.core.ast.Predicate
import org.eventb.core.ast.ProductType
import org.eventb.core.ast.QuantifiedPredicate
import org.eventb.core.ast.RelationalPredicate
import org.eventb.core.ast.SetExtension
import org.eventb.core.ast.Type
import org.eventb.core.ast.UnaryExpression
import org.eventb.core.ast.UnaryPredicate

/** A tactic cannot be applied; the message says why. */
class TacticException(message: String) : RuntimeException(message)

/** Where a tactic applies: the goal, or a hypothesis, and a position in it for rewrites. */
class Target(val hyp: Predicate?, val position: IPosition = IPosition.ROOT)

/**
 * The tactics of Rodin's Proof Control and its context menus on hypotheses and goal, each
 * producing the rule of Rodin's reasoner of the same name.
 */
object Tactics {

    private const val SEQPROVER = "org.eventb.core.seqprover"
    const val PROB = "de.prob.eventb.disprover.core.disproverReasoner"

    /** A tactic as offered in the proving panel. */
    class Tactic(
        /** The name used in commands, as `ah`. */
        val name: String,
        /** What Rodin's menus call it. */
        val label: String,
        /** Whether it applies to the goal, to a hypothesis, or both. */
        val onGoal: Boolean,
        val onHyp: Boolean,
        /** What the user must type: an expression list, a predicate, or nothing. */
        val input: String? = null,
        /** Whether it applies at a sub-formula (a rewrite) rather than the whole predicate. */
        val positional: Boolean = false,
        val applicable: (Formula<*>) -> Boolean,
        val make: (Sequent, Target, String?) -> Rule,
    )

    private fun conjuncts(p: Predicate): List<Predicate> =
        if (p.tag == Formula.LAND) (p as AssociativePredicate).children.toList() else listOf(p)

    private fun disjuncts(p: Predicate): List<Predicate> =
        if (p.tag == Formula.LOR) (p as AssociativePredicate).children.toList() else listOf(p)

    /** ¬p, without double negation, as Rodin's DLib.makeNeg. */
    private fun FormulaFactory.not(p: Predicate): Predicate =
        if (p.tag == Formula.NOT) (p as UnaryPredicate).child else makeUnaryPredicate(Formula.NOT, p, null)
    private fun FormulaFactory.and(ps: List<Predicate>): Predicate = when (ps.size) {
        0 -> makeLiteralPredicate(Formula.BTRUE, null)
        1 -> ps[0]
        else -> makeAssociativePredicate(Formula.LAND, ps, null)
    }
    private fun FormulaFactory.or(ps: List<Predicate>): Predicate =
        if (ps.size == 1) ps[0] else makeAssociativePredicate(Formula.LOR, ps, null)
    private fun FormulaFactory.rel(tag: Int, l: Expression, r: Expression): Predicate = makeRelationalPredicate(tag, l, r, null)
    private fun FormulaFactory.imp(l: Predicate, r: Predicate): Predicate = makeBinaryPredicate(Formula.LIMP, l, r, null)

    /** The well-definedness of [formulas], as conjuncts, without ⊤. */
    private fun wd(formulas: List<Formula<*>>): List<Predicate> =
        formulas.flatMap { conjuncts(it.wdPredicate) }.filter { it.tag != Formula.BTRUE }.distinct()

    private fun hypOf(target: Target) = target.hyp ?: throw TacticException("this tactic applies to a hypothesis")

    private fun parsePredicate(sequent: Sequent, text: String?): Predicate {
        val source = text?.trim().orEmpty()
        if (source.isEmpty()) throw TacticException("a predicate is needed")
        val result = sequent.factory.parsePredicate(source, null)
        if (result.hasProblem()) throw TacticException("cannot parse $source: ${result.problems.joinToString { it.toString() }}")
        val predicate = result.parsedPredicate
        val check = predicate.typeCheck(sequent.env)
        if (check.hasProblem()) throw TacticException("cannot type $source: ${check.problems.joinToString { it.toString() }}")
        if (!check.inferredEnvironment.isEmpty) throw TacticException("$source mentions unknown identifiers: ${check.inferredEnvironment.names.joinToString()}")
        return predicate
    }

    /** Parses the comma-separated instantiations of [decls]; an empty entry leaves the identifier bound. */
    private fun parseExpressions(sequent: Sequent, text: String?, decls: Array<BoundIdentDecl>): List<Expression?> {
        val parts = splitTopLevel(text.orEmpty())
        if (parts.size > decls.size) throw TacticException("${decls.size} bound identifier(s), ${parts.size} given")
        return decls.mapIndexed { i, decl ->
            val source = parts.getOrNull(i)?.trim().orEmpty()
            if (source.isEmpty() || source == "_") return@mapIndexed null
            val result = sequent.factory.parseExpression(source, null)
            if (result.hasProblem()) throw TacticException("cannot parse $source: ${result.problems.joinToString { it.toString() }}")
            val expression = result.parsedExpression
            val check = expression.typeCheck(sequent.env, decl.type)
            if (check.hasProblem()) throw TacticException("cannot type $source as ${decl.type}: ${check.problems.joinToString { it.toString() }}")
            if (!check.inferredEnvironment.isEmpty) throw TacticException("$source mentions unknown identifiers: ${check.inferredEnvironment.names.joinToString()}")
            expression
        }.also { if (it.all { e -> e == null }) throw TacticException("instantiations are needed, as `x + 1, ∅`") }
    }

    /** Splits [text] at the commas outside brackets. */
    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in text) {
            when (c) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
            }
            if (c == ',' && depth == 0) { parts += current.toString(); current.clear() } else current.append(c)
        }
        if (current.isNotBlank() || parts.isNotEmpty()) parts += current.toString()
        return parts
    }

    /** [quantified] with [values] for its bound identifiers; null values stay bound. */
    private fun instantiate(quantified: QuantifiedPredicate, values: List<Expression?>): Predicate =
        quantified.instantiate(values.toTypedArray(), quantified.factory)

    // ----------------------------------------------------------------------
    // Rules closing a sequent, also tried after every step, as Rodin's post-tactic

    fun trueGoal(s: Sequent): Rule? = if (s.goal.tag == Formula.BTRUE) Rule("$SEQPROVER.trueGoal", "⊤ goal", s.goal, emptySet(), emptyList()) else null

    fun falseHyp(s: Sequent): Rule? {
        val bottom = s.factory.makeLiteralPredicate(Formula.BFALSE, null)
        return if (s.contains(bottom)) Rule("$SEQPROVER.falseHyp", "⊥ hyp", null, setOf(bottom), emptyList()) else null
    }

    fun hyp(s: Sequent): Rule? = if (s.contains(s.goal)) Rule("$SEQPROVER.hyp", "hyp", s.goal, setOf(s.goal), emptyList()) else null

    fun contrHyps(s: Sequent, hyp: Predicate? = null): Rule? {
        for (h in hyp?.let { listOf(it) } ?: s.hyps.keys.toList()) {
            val negated = s.factory.not(h)
            if (s.contains(negated)) return Rule("$SEQPROVER.contrHyps:1", "ct in hyps ($h)", null, setOf(h, negated), emptyList())
        }
        return null
    }

    /** The rule closing [s] without a choice, if any. */
    fun close(s: Sequent): Rule? = trueGoal(s) ?: falseHyp(s) ?: hyp(s) ?: contrHyps(s)

    // ----------------------------------------------------------------------
    // Inference rules

    private fun impI(s: Sequent): Rule {
        if (s.goal.tag != Formula.LIMP) throw TacticException("the goal is not an implication")
        val goal = s.goal as BinaryPredicate
        return Rule("$SEQPROVER.impI", "⇒ goal", s.goal, emptySet(), listOf(Antecedent(goal.right, conjuncts(goal.left))))
    }

    private fun allI(s: Sequent): Rule {
        if (s.goal.tag != Formula.FORALL) throw TacticException("the goal is not universally quantified")
        val goal = s.goal as QuantifiedPredicate
        val fresh = s.env.makeBuilder().makeFreshIdentifiers(goal.boundIdentDecls).toList()
        return Rule("$SEQPROVER.allI", "∀ goal (frees ${fresh.joinToString(",")})", s.goal, emptySet(),
            listOf(Antecedent(instantiate(goal, fresh), idents = fresh)))
    }

    private fun conj(s: Sequent): Rule {
        if (s.goal.tag != Formula.LAND) throw TacticException("the goal is not a conjunction")
        return Rule("$SEQPROVER.conj:0", "∧ goal", s.goal, emptySet(), conjuncts(s.goal).map { Antecedent(it) })
    }

    private fun disjE(s: Sequent, hyp: Predicate): Rule {
        val deselect = listOf(HypAction.Selection(HypAction.DESELECT, listOf(hyp)))
        val cases: List<Predicate> = when {
            hyp.tag == Formula.LOR -> disjuncts(hyp)
            hyp.tag == Formula.IN && (hyp as RelationalPredicate).right.tag == Formula.BUNION ->
                (hyp.right as AssociativeExpression).children.map { s.factory.rel(Formula.IN, hyp.left, it) }
            hyp.tag == Formula.IN && (hyp as RelationalPredicate).right.tag == Formula.SETEXT && (hyp.right as SetExtension).members.size > 1 ->
                (hyp.right as SetExtension).members.map { s.factory.rel(Formula.EQUAL, hyp.left, it) }
            else -> throw TacticException("the hypothesis is not a disjunction")
        }
        return Rule("$SEQPROVER.disjE", "case distinction ($hyp)", null, setOf(hyp),
            cases.map { Antecedent(null, conjuncts(it), actions = deselect) })
    }

    private fun exE(s: Sequent, hyp: Predicate): Rule {
        if (hyp.tag != Formula.EXISTS) throw TacticException("the hypothesis is not existentially quantified")
        val quantified = hyp as QuantifiedPredicate
        val fresh = s.env.makeBuilder().makeFreshIdentifiers(quantified.boundIdentDecls).toList()
        return Rule("$SEQPROVER.exE", "∃ hyp", s.goal, setOf(hyp), listOf(Antecedent(s.goal, conjuncts(instantiate(quantified, fresh)), idents = fresh,
            actions = listOf(HypAction.Selection(HypAction.DESELECT, listOf(hyp))))))
    }

    private fun conjF(s: Sequent, hyp: Predicate): Rule {
        if (hyp.tag != Formula.LAND) throw TacticException("the hypothesis is not a conjunction")
        return Rule("$SEQPROVER.conjF", "∧ hyp ($hyp)", null, setOf(hyp), listOf(Antecedent(null, actions = listOf(
            HypAction.Inference(HypAction.FORWARD_INF, listOf(hyp), emptyList(), conjuncts(hyp)),
            HypAction.Selection(HypAction.HIDE, listOf(hyp))))))
    }

    private fun allD(s: Sequent, hyp: Predicate, text: String?): Rule {
        if (hyp.tag != Formula.FORALL) throw TacticException("the hypothesis is not universally quantified")
        val quantified = hyp as QuantifiedPredicate
        val values = parseExpressions(s, text, quantified.boundIdentDecls)
        val wds = wd(values.filterNotNull())
        val instance = instantiate(quantified, values)
        return Rule("$SEQPROVER.allD", "∀ hyp (inst ${values.joinToString(",") { it?.toString() ?: "" }})", null, setOf(hyp),
            listOf(Antecedent(s.factory.and(wds)), Antecedent(null, wds + conjuncts(instance), wds,
                actions = listOf(HypAction.Selection(HypAction.DESELECT, listOf(hyp))))),
            inputs = listOf(RuleInput.Expressions("exprs", values)))
    }

    private fun exI(s: Sequent, text: String?): Rule {
        if (s.goal.tag != Formula.EXISTS) throw TacticException("the goal is not existentially quantified")
        val quantified = s.goal as QuantifiedPredicate
        val values = parseExpressions(s, text, quantified.boundIdentDecls)
        val wds = wd(values.filterNotNull())
        return Rule("$SEQPROVER.exI", "∃ goal (inst ${values.joinToString(",") { it?.toString() ?: "" }})", s.goal, emptySet(),
            listOf(Antecedent(s.factory.and(wds)), Antecedent(instantiate(quantified, values), wds)),
            inputs = listOf(RuleInput.Expressions("exprs", values)))
    }

    private fun impE(s: Sequent, hyp: Predicate): Rule {
        if (hyp.tag != Formula.LIMP) throw TacticException("the hypothesis is not an implication")
        val implication = hyp as BinaryPredicate
        val hide = listOf(HypAction.Selection(HypAction.HIDE, listOf(hyp)))
        return Rule("$SEQPROVER.impE:2", "⇒ hyp mp ($hyp)", null, setOf(hyp),
            listOf(Antecedent(implication.left, actions = hide), Antecedent(null, conjuncts(implication.right), actions = hide)))
    }

    private fun cut(s: Sequent, text: String?): Rule {
        val lemma = parsePredicate(s, text)
        val wds = wd(listOf(lemma))
        return Rule("$SEQPROVER.cut", "ah ($lemma)", null, emptySet(),
            listOf(Antecedent(s.factory.and(wds)), Antecedent(lemma, wds), Antecedent(null, wds + lemma)),
            inputs = listOf(RuleInput.Predicates("pred", listOf(lemma))))
    }

    private fun doCase(s: Sequent, text: String?): Rule {
        val case = parsePredicate(s, text)
        return Rule("$SEQPROVER.doCase", "dc ($case)", null, emptySet(),
            listOf(Antecedent(s.factory.and(wd(listOf(case)))), Antecedent(null, listOf(case)), Antecedent(null, listOf(s.factory.not(case)))),
            inputs = listOf(RuleInput.Predicates("pred", listOf(case))))
    }

    private fun contr(s: Sequent, hyp: Predicate?): Rule {
        val negatedGoal = conjuncts(s.factory.not(s.goal))
        return if (hyp == null) {
            Rule("$SEQPROVER.contr", "ct goal", s.goal, emptySet(), listOf(Antecedent(s.factory.makeLiteralPredicate(Formula.BFALSE, null), negatedGoal)))
        } else {
            Rule("$SEQPROVER.contr", "ct hyp ($hyp)", s.goal, setOf(hyp), listOf(Antecedent(s.factory.not(hyp), negatedGoal,
                actions = listOf(HypAction.Selection(HypAction.DESELECT, listOf(hyp))))))
        }
    }

    /** Replaces [from] by [to] everywhere in [p]. */
    private fun replace(p: Predicate, from: Expression, to: Expression): Predicate {
        var result = p
        for ((position, _) in positionsOf(p).filter { (_, sub) -> sub == from }.reversed()) {
            result = result.rewriteSubFormula(position, to)
        }
        return result
    }

    /** eh (left to right) and he (right to left): rewrites with an equality hypothesis. */
    private fun eq(s: Sequent, hyp: Predicate, leftToRight: Boolean): Rule {
        if (hyp.tag != Formula.EQUAL) throw TacticException("the hypothesis is not an equality")
        val equality = hyp as RelationalPredicate
        val (from, to) = if (leftToRight) equality.left to equality.right else equality.right to equality.left
        val newGoal = replace(s.goal, from, to)
        val actions = s.hyps.keys.filter { it != hyp }.mapNotNull { h ->
            val rewritten = replace(h, from, to)
            if (rewritten == h) null else HypAction.Inference(HypAction.REWRITE, listOf(h), emptyList(), listOf(rewritten), listOf(h))
        }
        if (newGoal == s.goal && actions.isEmpty()) throw TacticException("$from does not occur elsewhere")
        val (id, name) = if (leftToRight) "eq:1" to "eh" else "he:1" to "he"
        return Rule("$SEQPROVER.$id", "$name with $hyp", if (newGoal == s.goal) null else s.goal, setOf(hyp),
            listOf(Antecedent(if (newGoal == s.goal) null else newGoal, actions = actions)))
    }

    private fun selection(s: Sequent, hyp: Predicate, type: String): Rule =
        Rule("$SEQPROVER.mngHyp", "sl/ds", null, emptySet(), listOf(Antecedent(null, actions = listOf(HypAction.Selection(type, listOf(hyp))))))

    /** Marks the node as reviewed: the user takes its correctness for granted. */
    fun review(s: Sequent): Rule {
        val confidence = Confidence.REVIEWED_MAX
        return Rule("$SEQPROVER.review", "rv ($confidence)", s.goal, s.hyps.filterValues { it == HypState.SELECTED }.keys, emptyList(), confidence)
    }

    /** ProB found no counterexample to the sequent, with all its hypotheses. */
    fun prob(s: Sequent, used: Set<Predicate>): Rule =
        Rule(PROB, "ProB (no enumeration / all cases checked)", s.goal, used, emptyList())

    // ----------------------------------------------------------------------
    // Rewrites at a position, as Rodin's manual rewriters

    /**
     * A rewrite at a position: [rewrite] gives the new sub-formula. On the goal, each conjunct
     * of the result becomes a goal; on a hypothesis, the result replaces it.
     */
    private fun rewriteRule(s: Sequent, target: Target, id: String, what: String, rewrite: (Formula<*>) -> Formula<*>?): Rule {
        val pred = target.hyp ?: s.goal
        val sub = pred.getSubFormula(target.position) ?: throw TacticException("no sub-formula at ${target.position}")
        val replacement = rewrite(sub) ?: throw TacticException("cannot $what in $sub")
        val result = pred.rewriteSubFormula(target.position, replacement)
        val input = listOf(RuleInput.Text("pos", target.position.toString()))
        val hyp = target.hyp
        return if (hyp == null) {
            Rule("$SEQPROVER.$id", "$what in goal", s.goal, emptySet(), conjuncts(result).map { Antecedent(it) }, inputs = input)
        } else {
            val inferred = conjuncts(result).filter { it.tag != Formula.BTRUE }
            val actions = if (inferred.isEmpty()) listOf(HypAction.Selection(HypAction.HIDE, listOf(hyp)))
            else listOf(HypAction.Inference(HypAction.REWRITE, listOf(hyp), emptyList(), inferred, listOf(hyp)), HypAction.Selection(HypAction.SELECT, inferred))
            Rule("$SEQPROVER.$id", "$what in $sub", null, setOf(hyp), listOf(Antecedent(null, actions = actions)), inputs = input)
        }
    }

    /** rn: pushes a negation inwards. */
    private fun removeNegation(sub: Formula<*>): Formula<*>? {
        if (sub.tag != Formula.NOT) return null
        val ff = sub.factory
        val child = (sub as UnaryPredicate).child
        return when (child.tag) {
            Formula.LAND -> ff.or(conjuncts(child).map { ff.not(it) })
            Formula.LOR -> ff.and(disjuncts(child).map { ff.not(it) })
            Formula.NOT -> (child as UnaryPredicate).child
            Formula.LIMP -> (child as BinaryPredicate).let { ff.and(listOf(it.left, ff.not(it.right))) }
            Formula.BTRUE -> ff.makeLiteralPredicate(Formula.BFALSE, null)
            Formula.BFALSE -> ff.makeLiteralPredicate(Formula.BTRUE, null)
            Formula.FORALL, Formula.EXISTS -> (child as QuantifiedPredicate).let {
                ff.makeQuantifiedPredicate(if (child.tag == Formula.FORALL) Formula.EXISTS else Formula.FORALL, it.boundIdentDecls, ff.not(it.predicate), null)
            }
            else -> null
        }
    }

    /** A bound identifier of type [type] at de Bruijn index [index]. */
    private fun bound(ff: FormulaFactory, index: Int, type: Type) = ff.makeBoundIdentifier(index, null, type)

    /** rm: rewrites a membership by the definition of the set. */
    private fun removeMembership(sub: Formula<*>): Formula<*>? {
        if (sub.tag != Formula.IN) return null
        val ff = sub.factory
        val element = (sub as RelationalPredicate).left
        val set = sub.right
        fun member(e: Expression, s: Expression) = ff.rel(Formula.IN, e, s)
        return when (set.tag) {
            Formula.BUNION -> ff.or((set as AssociativeExpression).children.map { member(element, it) })
            Formula.BINTER -> ff.and((set as AssociativeExpression).children.map { member(element, it) })
            Formula.SETMINUS -> (set as BinaryExpression).let { ff.and(listOf(member(element, it.left), ff.not(member(element, it.right)))) }
            Formula.POW -> ff.rel(Formula.SUBSETEQ, element, (set as UnaryExpression).child)
            Formula.POW1 -> ff.and(listOf(ff.rel(Formula.SUBSETEQ, element, (set as UnaryExpression).child),
                ff.not(ff.rel(Formula.EQUAL, element, ff.makeEmptySet(element.type, null)))))
            Formula.SETEXT -> ff.or((set as SetExtension).members.map { ff.rel(Formula.EQUAL, element, it) }.ifEmpty { return ff.makeLiteralPredicate(Formula.BFALSE, null) })
            Formula.EMPTYSET -> ff.makeLiteralPredicate(Formula.BFALSE, null)
            Formula.UPTO -> (set as BinaryExpression).let { ff.and(listOf(ff.rel(Formula.LE, it.left, element), ff.rel(Formula.LE, element, it.right))) }
            Formula.NATURAL -> ff.rel(Formula.LE, ff.makeIntegerLiteral(java.math.BigInteger.ZERO, null), element)
            Formula.NATURAL1 -> ff.rel(Formula.LE, ff.makeIntegerLiteral(java.math.BigInteger.ONE, null), element)
            Formula.CONVERSE -> if (element.tag == Formula.MAPSTO) (element as BinaryExpression).let {
                member(ff.makeBinaryExpression(Formula.MAPSTO, it.right, it.left, null), (set as UnaryExpression).child)
            } else null
            Formula.CPROD -> if (element.tag == Formula.MAPSTO) {
                val pair = element as BinaryExpression
                val product = set as BinaryExpression
                ff.and(listOf(member(pair.left, product.left), member(pair.right, product.right)))
            } else null
            Formula.KDOM, Formula.KRAN -> {
                // x ∈ dom(r) ⇔ ∃y·x ↦ y ∈ r, and x ∈ ran(r) ⇔ ∃y·y ↦ x ∈ r
                val relation = (set as UnaryExpression).child
                val types = relation.type.source to relation.type.target
                val otherType = if (set.tag == Formula.KDOM) types.second else types.first
                val decl = ff.makeBoundIdentDecl(if (set.tag == Formula.KDOM) "y" else "x", null, otherType)
                val y = bound(ff, 0, otherType)
                val e = element.shiftBoundIdentifiers(1)
                val pair = if (set.tag == Formula.KDOM) ff.makeBinaryExpression(Formula.MAPSTO, e, y, null) else ff.makeBinaryExpression(Formula.MAPSTO, y, e, null)
                ff.makeQuantifiedPredicate(Formula.EXISTS, arrayOf(decl), member(pair, relation.shiftBoundIdentifiers(1)), null)
            }
            Formula.RELIMAGE -> {
                // y ∈ r[S] ⇔ ∃x·x ∈ S ∧ x ↦ y ∈ r
                val image = set as BinaryExpression
                val sourceType = image.left.type.source
                val decl = ff.makeBoundIdentDecl("x", null, sourceType)
                val x = bound(ff, 0, sourceType)
                ff.makeQuantifiedPredicate(Formula.EXISTS, arrayOf(decl), ff.and(listOf(
                    member(x, image.right.shiftBoundIdentifiers(1)),
                    member(ff.makeBinaryExpression(Formula.MAPSTO, x, element.shiftBoundIdentifiers(1), null), image.left.shiftBoundIdentifiers(1)))), null)
            }
            else -> null
        }
    }

    private val Type.source: Type get() = ((this as PowerSetType).baseType as ProductType).left
    private val Type.target: Type get() = ((this as PowerSetType).baseType as ProductType).right

    /** ri: A ⊆ B ⇔ ∀x·x ∈ A ⇒ x ∈ B. */
    private fun removeInclusion(sub: Formula<*>): Formula<*>? {
        if (sub.tag != Formula.SUBSETEQ) return null
        val ff = sub.factory
        val inclusion = sub as RelationalPredicate
        val type = (inclusion.left.type as PowerSetType).baseType
        val x = bound(ff, 0, type)
        return ff.makeQuantifiedPredicate(Formula.FORALL, arrayOf(ff.makeBoundIdentDecl("x", null, type)),
            ff.imp(ff.rel(Formula.IN, x, inclusion.left.shiftBoundIdentifiers(1)), ff.rel(Formula.IN, x, inclusion.right.shiftBoundIdentifiers(1))), null)
    }

    /** Set equality as two inclusions. */
    private fun setEquality(sub: Formula<*>): Formula<*>? {
        if (sub.tag != Formula.EQUAL || (sub as RelationalPredicate).left.type !is PowerSetType) return null
        val ff = sub.factory
        return ff.and(listOf(ff.rel(Formula.SUBSETEQ, sub.left, sub.right), ff.rel(Formula.SUBSETEQ, sub.right, sub.left)))
    }

    /** P ⇔ Q as two implications. */
    private fun equivalence(sub: Formula<*>): Formula<*>? {
        if (sub.tag != Formula.LEQV) return null
        val ff = sub.factory
        val eqv = sub as BinaryPredicate
        return ff.and(listOf(ff.imp(eqv.left, eqv.right), ff.imp(eqv.right, eqv.left)))
    }

    /**
     * funOvr: a case distinction on (f  g)(x): x ∈ dom(g) gives g(x), x ∉ dom(g) gives f(x).
     */
    private fun funOvr(s: Sequent, target: Target): Rule {
        val pred = target.hyp ?: s.goal
        val sub = pred.getSubFormula(target.position) as? BinaryExpression
        if (sub == null || sub.tag != Formula.FUNIMAGE || sub.left.tag != Formula.OVR) throw TacticException("not an application of an overriding")
        if (sub.boundIdentifiers.isNotEmpty()) throw TacticException("the application mentions bound identifiers")
        val ff = s.factory
        val override = sub.left as AssociativeExpression
        val last = override.children.last()
        val rest = if (override.children.size == 2) override.children[0] else ff.makeAssociativeExpression(Formula.OVR, override.children.dropLast(1), null)
        val inDomain = ff.rel(Formula.IN, sub.right, ff.makeUnaryExpression(Formula.KDOM, last, null))
        val cases = listOf(inDomain to last, ff.not(inDomain) to rest)
        val input = listOf(RuleInput.Text("pos", target.position.toString()))
        val hyp = target.hyp
        return if (hyp == null) {
            Rule("$SEQPROVER.funOvr:1", "ovr", s.goal, emptySet(), cases.map { (case, f) ->
                Antecedent(pred.rewriteSubFormula(target.position, ff.makeBinaryExpression(Formula.FUNIMAGE, f, sub.right, null)), listOf(case))
            }, inputs = input)
        } else {
            Rule("$SEQPROVER.funOvr:1", "ovr", null, setOf(hyp), cases.map { (case, f) ->
                val rewritten = pred.rewriteSubFormula(target.position, ff.makeBinaryExpression(Formula.FUNIMAGE, f, sub.right, null))
                Antecedent(null, listOf(case), actions = listOf(
                    HypAction.Inference(HypAction.REWRITE, listOf(hyp), emptyList(), listOf(rewritten), listOf(hyp))))
            }, inputs = input)
        }
    }

    private fun rewrite(name: String, label: String, id: String, what: String, f: (Formula<*>) -> Formula<*>?) = Tactic(
        name, label, onGoal = true, onHyp = true, positional = true, applicable = { f(it) != null },
        make = { s, t, _ -> rewriteRule(s, t, id, what, f) })

    /** Every tactic, in the order of Rodin's menus. */
    val all: List<Tactic> = listOf(
        Tactic("impI", "⇒ goal: assume the left side", true, false, applicable = { it.tag == Formula.LIMP }) { s, _, _ -> impI(s) },
        Tactic("allI", "∀ goal: free the bound identifiers", true, false, applicable = { it.tag == Formula.FORALL }) { s, _, _ -> allI(s) },
        Tactic("conj", "∧ goal: prove each conjunct", true, false, applicable = { it.tag == Formula.LAND }) { s, _, _ -> conj(s) },
        Tactic("exI", "∃ goal: instantiate", true, false, input = "instantiations, as `1, ∅`", applicable = { it.tag == Formula.EXISTS }) { s, _, i -> exI(s, i) },
        Tactic("disjE", "case distinction", false, true, applicable = {
            it.tag == Formula.LOR || (it.tag == Formula.IN && ((it as RelationalPredicate).right.tag == Formula.BUNION ||
                (it.right.tag == Formula.SETEXT && (it.right as SetExtension).members.size > 1)))
        }) { s, t, _ -> disjE(s, hypOf(t)) },
        Tactic("conjF", "∧ hyp: split", false, true, applicable = { it.tag == Formula.LAND }) { s, t, _ -> conjF(s, hypOf(t)) },
        Tactic("exE", "∃ hyp: free the bound identifiers", false, true, applicable = { it.tag == Formula.EXISTS }) { s, t, _ -> exE(s, hypOf(t)) },
        Tactic("allD", "∀ hyp: instantiate", false, true, input = "instantiations, as `1, ∅`", applicable = { it.tag == Formula.FORALL }) { s, t, i -> allD(s, hypOf(t), i) },
        Tactic("impE", "⇒ hyp: modus ponens", false, true, applicable = { it.tag == Formula.LIMP }) { s, t, _ -> impE(s, hypOf(t)) },
        Tactic("eh", "eh: rewrite left to right everywhere", false, true, applicable = { it.tag == Formula.EQUAL }) { s, t, _ -> eq(s, hypOf(t), true) },
        Tactic("he", "he: rewrite right to left everywhere", false, true, applicable = { it.tag == Formula.EQUAL }) { s, t, _ -> eq(s, hypOf(t), false) },
        Tactic("ct", "ct: contradict", true, true, applicable = { true }) { s, t, _ -> contr(s, t.hyp) },
        Tactic("ah", "ah: add a hypothesis", true, false, input = "a predicate to prove first", applicable = { true }) { s, _, i -> cut(s, i) },
        Tactic("dc", "dc: case distinction on a predicate", true, false, input = "the predicate distinguishing the cases", applicable = { true }) { s, _, i -> doCase(s, i) },
        rewrite("rn", "remove ¬", "rn", "remove ¬", ::removeNegation),
        rewrite("rm", "remove ∈", "rm", "remove ∈", ::removeMembership),
        rewrite("ri", "remove ⊆", "ri", "remove ⊆", ::removeInclusion),
        rewrite("setEql", "set equality as ⊆ both ways", "setEqlRewrites", "rewrites set equality", ::setEquality),
        rewrite("eqv", "⇔ as ⇒ both ways", "eqvRewrites", "rewrites equivalence", ::equivalence),
        Tactic("funOvr", "function overriding: case distinction", true, true, positional = true, applicable = {
            it.tag == Formula.FUNIMAGE && (it as BinaryExpression).left.tag == Formula.OVR && it.boundIdentifiers.isEmpty()
        }) { s, t, _ -> funOvr(s, t) },
        Tactic("select", "select", false, true, applicable = { true }) { s, t, _ -> selection(s, hypOf(t), HypAction.SELECT) },
        Tactic("deselect", "deselect", false, true, applicable = { true }) { s, t, _ -> selection(s, hypOf(t), HypAction.DESELECT) },
        Tactic("hide", "hide", false, true, applicable = { true }) { s, t, _ -> selection(s, hypOf(t), HypAction.HIDE) },
        Tactic("show", "show", false, true, applicable = { true }) { s, t, _ -> selection(s, hypOf(t), HypAction.SHOW) },
    )

    fun named(name: String) = all.firstOrNull { it.name == name } ?: throw TacticException("unknown tactic $name")

    /** The tactics applying to [predicate] (the goal if [onGoal]), with their positions. */
    fun applicable(predicate: Predicate, onGoal: Boolean): List<Pair<Tactic, IPosition>> {
        val result = mutableListOf<Pair<Tactic, IPosition>>()
        val positions = positionsOf(predicate)
        for (tactic in all) {
            if (if (onGoal) !tactic.onGoal else !tactic.onHyp) continue
            if (tactic.positional) {
                positions.filter { (_, sub) -> runCatching { tactic.applicable(sub) }.getOrDefault(false) }.forEach { (p, _) -> result += tactic to p }
            } else if (tactic.applicable(predicate)) {
                result += tactic to IPosition.ROOT
            }
        }
        return result
    }
}
