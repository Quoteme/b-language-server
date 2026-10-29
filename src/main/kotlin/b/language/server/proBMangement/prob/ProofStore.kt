package b.language.server.proBMangement.prob

import org.eventb.core.ast.Expression
import org.eventb.core.ast.Formula
import org.eventb.core.ast.FormulaFactory
import org.eventb.core.ast.FreeIdentifier
import org.eventb.core.ast.ITypeEnvironment
import org.eventb.core.ast.ITypeEnvironmentBuilder
import org.eventb.core.ast.Predicate
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * The proofs of a component in Rodin's files: `<component>.bpr` holds the proof trees,
 * `<component>.bps` the status of each obligation. The XML is Rodin's, so Rodin reuses the
 * proofs made here once the component is imported, and proofs made in Rodin are replayed here.
 */
class ProofStore(private val component: File) {

    private val base = component.absoluteFile.parentFile
    private val name = component.nameWithoutExtension
    val proofFile = File(base, "$name.bpr")
    val statusFile = File(base, "$name.bps")
    private val factory = FormulaFactory.getDefault()

    companion object {
        private const val CORE = "org.eventb.core"
        private const val PR_FILE = "$CORE.prFile"
        private const val PS_FILE = "$CORE.psFile"
        private const val PROOF = "$CORE.prProof"
        private const val STATUS = "$CORE.psStatus"
        private const val RULE = "$CORE.prRule"
        private const val ANTE = "$CORE.prAnte"
        private const val HYP_ACTION = "$CORE.prHypAction"
        private const val IDENT = "$CORE.prIdent"
        private const val PRED = "$CORE.prPred"
        private const val EXPR = "$CORE.prExpr"
        private const val REASONER = "$CORE.prReas"
        private const val STRING = "$CORE.prString"
        private const val PRED_REF = "$CORE.prPredRef"
        private const val EXPR_REF = "$CORE.prExprRef"

        private const val CONFIDENCE = "$CORE.confidence"
        private const val MANUAL = "$CORE.psManual"
        private const val BROKEN = "$CORE.psBroken"
        private const val GOAL = "$CORE.prGoal"
        private const val HYPS = "$CORE.prHyps"
        private const val UNSEL = "$CORE.prUnsel"
        private const val HIDDEN = "$CORE.prHidden"
        private const val INF_HYPS = "$CORE.prInfHyps"
        private const val DISPLAY = "$CORE.prDisplay"
        private const val REF = "$CORE.prRef"
        private const val VALUE = "$CORE.prSValue"
        private const val FRESH = "$CORE.prFresh"
        private const val SETS = "$CORE.prSets"
        private const val REASONER_ID = "$CORE.prRID"
        private const val TYPE = "$CORE.type"
        private const val PREDICATE = "$CORE.predicate"
        private const val EXPRESSION = "$CORE.expression"
    }

    // ----------------------------------------------------------------------
    // Reading

    /** The saved proofs, by obligation name; proofs that cannot be read are left out. */
    fun load(): Map<String, Skeleton> {
        val document = read(proofFile) ?: return emptyMap()
        val result = LinkedHashMap<String, Skeleton>()
        for (proof in children(document.documentElement, PROOF)) {
            try {
                ProofReader(proof).root()?.let { result[proof.getAttribute("name")] = it }
            } catch (e: RuntimeException) {
                // A proof Rodin wrote with formulas we cannot read stays in the file untouched.
            }
        }
        return result
    }

    private fun read(file: File): Document? {
        if (!file.isFile) return null
        return try {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        } catch (e: Exception) {
            null
        }
    }

    private fun children(element: Element, tag: String): List<Element> {
        val result = mutableListOf<Element>()
        var node = element.firstChild
        while (node != null) {
            if (node is Element && node.tagName == tag) result += node
            node = node.nextSibling
        }
        return result
    }

    private fun refs(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(",")

    /** Reads one prProof element: its type environment, formula pools and rule tree. */
    private inner class ProofReader(private val proof: Element) {
        private val env: ITypeEnvironment
        private val reasoners = children(proof, REASONER).associate { it.getAttribute("name") to it.getAttribute(REASONER_ID) }
        private val predicates = HashMap<String, Predicate>()
        private val expressions = HashMap<String, Expression>()

        init {
            val builder = factory.makeTypeEnvironment()
            refs(proof.getAttribute(SETS)).forEach { builder.addGivenSet(it) }
            addIdents(builder, proof)
            env = builder.makeSnapshot()
            for (p in children(proof, PRED)) {
                val local = env.makeBuilder().also { addIdents(it, p) }
                predicates[p.getAttribute("name")] = parsePredicate(p.getAttribute(PREDICATE), local)
            }
            for (e in children(proof, EXPR)) {
                val local = env.makeBuilder().also { addIdents(it, e) }
                expressions[e.getAttribute("name")] = parseExpression(e.getAttribute(EXPRESSION), local)
            }
        }

        private fun addIdents(builder: ITypeEnvironmentBuilder, element: Element) {
            for (ident in children(element, IDENT)) builder.addName(ident.getAttribute("name"), parseType(ident.getAttribute(TYPE), builder))
        }

        private fun idents(element: Element): List<FreeIdentifier> = children(element, IDENT).map {
            factory.makeFreeIdentifier(it.getAttribute("name"), null, parseType(it.getAttribute(TYPE), env.makeBuilder()))
        }

        private fun parseType(text: String, builder: ITypeEnvironmentBuilder) =
            factory.parseType(text).parsedType ?: error("cannot read type $text")

        private fun parsePredicate(text: String, builder: ITypeEnvironmentBuilder): Predicate {
            val result = factory.parsePredicate(text, null)
            check(!result.hasProblem()) { "cannot read predicate $text" }
            val predicate = result.parsedPredicate
            check(!predicate.typeCheck(builder).hasProblem()) { "cannot type predicate $text" }
            return predicate
        }

        private fun parseExpression(text: String, builder: ITypeEnvironmentBuilder): Expression {
            val result = factory.parseExpression(text, null)
            check(!result.hasProblem()) { "cannot read expression $text" }
            val expression = result.parsedExpression
            check(!expression.typeCheck(builder).hasProblem()) { "cannot type expression $text" }
            return expression
        }

        private fun predicate(ref: String) = predicates[ref] ?: error("unknown predicate $ref")
        private fun preds(element: Element, attribute: String) =
            if (element.hasAttribute(attribute)) refs(element.getAttribute(attribute)).map { predicate(it) } else emptyList()

        fun root(): Skeleton? = children(proof, RULE).firstOrNull()?.let { skeleton(it) }

        private fun skeleton(rule: Element): Skeleton {
            val antecedents = children(rule, ANTE).sortedBy { it.getAttribute("name").toIntOrNull() ?: 0 }
            val inputs = mutableListOf<RuleInput>()
            for (s in children(rule, STRING)) inputs += RuleInput.Text(s.getAttribute("name").removePrefix("."), s.getAttribute(VALUE))
            for (s in children(rule, PRED_REF)) inputs += RuleInput.Predicates(s.getAttribute("name").removePrefix("."), refs(s.getAttribute(REF)).map { predicate(it) })
            for (s in children(rule, EXPR_REF)) {
                inputs += RuleInput.Expressions(s.getAttribute("name").removePrefix("."), s.getAttribute(REF).split(",").map { r -> if (r.isEmpty()) null else expressions[r] })
            }
            val ref = rule.getAttribute("name")
            val parsed = Rule(
                reasoner = reasoners[ref] ?: ref,
                display = rule.getAttribute(DISPLAY),
                goal = if (rule.hasAttribute(GOAL)) predicate(rule.getAttribute(GOAL)) else null,
                needed = preds(rule, HYPS).toSet(),
                antecedents = antecedents.map { antecedent(it) },
                confidence = rule.getAttribute(CONFIDENCE).toIntOrNull() ?: Confidence.DISCHARGED_MAX,
                inputs = inputs,
            )
            return Skeleton(parsed, antecedents.map { a -> children(a, RULE).firstOrNull()?.let { skeleton(it) } ?: Skeleton(null, emptyList()) })
        }

        private fun antecedent(element: Element): Antecedent {
            val actions = children(element, HYP_ACTION).map { a ->
                val type = a.getAttribute("name").trimEnd { it.isDigit() }
                when (type) {
                    HypAction.FORWARD_INF -> HypAction.Inference(type, preds(a, HYPS), idents(a), preds(a, INF_HYPS))
                    HypAction.REWRITE -> {
                        val hidden = preds(a, HIDDEN)
                        HypAction.Inference(type, preds(a, HYPS) + hidden, idents(a), preds(a, INF_HYPS), hidden)
                    }
                    else -> HypAction.Selection(type, preds(a, HYPS))
                }
            }
            return Antecedent(
                goal = if (element.hasAttribute(GOAL)) predicate(element.getAttribute(GOAL)) else null,
                added = preds(element, HYPS),
                unselected = preds(element, UNSEL),
                idents = idents(element),
                actions = actions,
            )
        }
    }

    // ----------------------------------------------------------------------
    // Writing

    /**
     * Saves the proof of [tree]'s obligation, replacing its previous proof and keeping the others
     * as they are; an empty proof is removed. The status file is updated likewise.
     */
    fun save(tree: ProofTree) {
        val name = tree.po.name
        val skeleton = tree.root.skeleton()
        val document = read(proofFile) ?: newDocument(PR_FILE)
        children(document.documentElement, PROOF).filter { it.getAttribute("name") == name }.forEach { document.documentElement.removeChild(it) }
        if (skeleton.rule != null) document.documentElement.appendChild(ProofWriter(document, tree, skeleton).write())
        write(document, proofFile)

        val status = read(statusFile) ?: newDocument(PS_FILE)
        children(status.documentElement, STATUS).filter { it.getAttribute("name") == name }.forEach { status.documentElement.removeChild(it) }
        if (skeleton.rule != null) {
            val element = status.createElement(STATUS)
            element.setAttribute("name", name)
            element.setAttribute(CONFIDENCE, tree.confidence.toString())
            element.setAttribute(MANUAL, "true")
            element.setAttribute(BROKEN, tree.root.nodes().any { it.broken != null }.toString())
            status.documentElement.appendChild(element)
        }
        write(status, statusFile)
    }

    private fun newDocument(root: String): Document {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        document.xmlStandalone = false
        document.appendChild(document.createElement(root).apply { if (root == PR_FILE) setAttribute("version", "1") })
        return document
    }

    private fun write(document: Document, file: File) {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.STANDALONE, "no")
        // Re-indenting a file read back would add blank lines; strip them first.
        stripWhitespace(document.documentElement)
        file.outputStream().use { transformer.transform(DOMSource(document), StreamResult(it)) }
    }

    private fun stripWhitespace(element: Element) {
        var node = element.firstChild
        while (node != null) {
            val next = node.nextSibling
            if (node.nodeType == org.w3c.dom.Node.TEXT_NODE && node.textContent.isBlank()) element.removeChild(node)
            else if (node is Element) stripWhitespace(node)
            node = next
        }
    }

    /** Writes one prProof element, with the pools of formulas and reasoners as Rodin's ProofStoreCollector. */
    private inner class ProofWriter(private val document: Document, private val tree: ProofTree, private val skeleton: Skeleton) {
        private val predicates = LinkedHashMap<Predicate, String>()
        private val expressions = LinkedHashMap<Expression, String>()
        private val reasoners = LinkedHashMap<String, String>()
        private val dependencies = tree.dependencies()
        /** Identifiers in the obligation's type environment: the proof's base environment. */
        private val poEnv = tree.po.env

        private fun pred(p: Predicate) = predicates.getOrPut(p) { "p${predicates.size}" }
        private fun expr(e: Expression) = expressions.getOrPut(e) { "e${expressions.size}" }
        private fun preds(ps: Collection<Predicate>) = ps.joinToString(",") { pred(it) }

        fun write(): Element {
            val proof = document.createElement(PROOF)
            proof.setAttribute("name", tree.po.name)
            proof.setAttribute(CONFIDENCE, tree.confidence.toString())
            proof.setAttribute(FRESH, dependencies.introduced.joinToString(","))
            dependencies.goal?.let { proof.setAttribute(GOAL, pred(it)) }
            proof.setAttribute(HYPS, preds(dependencies.hyps))
            proof.setAttribute(MANUAL, "true")
            skeleton.rule?.let { proof.appendChild(rule(it, skeleton)) }

            // The base type environment: the obligation's identifiers the stored formulas mention.
            val mentioned = LinkedHashSet<String>()
            (predicates.keys + expressions.keys).forEach { f -> f.freeIdentifiers.forEach { mentioned += it.name } }
            val sets = LinkedHashSet<String>()
            val idents = mutableListOf<Pair<String, String>>()
            val iterator = poEnv.iterator
            while (iterator.hasNext()) {
                iterator.advance()
                if (iterator.name !in mentioned && !iterator.isGivenSet) continue
                if (iterator.isGivenSet) {
                    if (iterator.name in mentioned || typesMention(iterator.name)) sets += iterator.name
                } else {
                    idents += iterator.name to iterator.type.toString()
                }
            }
            if (sets.isNotEmpty()) proof.setAttribute(SETS, sets.joinToString(","))
            for ((n, type) in idents) proof.appendChild(ident(n, type))
            for ((p, ref) in predicates) proof.appendChild(stored(PRED, PREDICATE, ref, p))
            for ((e, ref) in expressions) proof.appendChild(stored(EXPR, EXPRESSION, ref, e))
            for ((id, ref) in reasoners) proof.appendChild(document.createElement(REASONER).apply {
                setAttribute("name", ref)
                setAttribute(REASONER_ID, id)
            })
            return proof
        }

        /** Whether a type in the stored formulas mentions the given set [set]. */
        private fun typesMention(set: String): Boolean = (predicates.keys + expressions.keys).any { f ->
            f.freeIdentifiers.any { i -> i.type.toExpression().freeIdentifiers.any { it.name == set } }
        }

        private fun ident(name: String, type: String) = document.createElement(IDENT).apply {
            setAttribute("name", name)
            setAttribute(TYPE, type)
        }

        /** A pooled formula; identifiers introduced by the proof are declared inside it, as Rodin does. */
        private fun stored(tag: String, attribute: String, ref: String, formula: Formula<*>) = document.createElement(tag).apply {
            setAttribute("name", ref)
            setAttribute(attribute, formula.toString())
            formula.freeIdentifiers.filter { !poEnv.contains(it.name) }.forEach { appendChild(ident(it.name, it.type.toString())) }
        }

        private fun rule(rule: Rule, skeleton: Skeleton): Element {
            val element = document.createElement(RULE)
            element.setAttribute("name", reasoners.getOrPut(rule.reasoner) { "r${reasoners.size}" })
            element.setAttribute(CONFIDENCE, rule.confidence.toString())
            element.setAttribute(DISPLAY, rule.display)
            rule.goal?.let { element.setAttribute(GOAL, pred(it)) }
            element.setAttribute(HYPS, preds(rule.needed))
            rule.antecedents.forEachIndexed { i, antecedent ->
                val child = antecedent(i, antecedent)
                skeleton.children.getOrNull(i)?.let { s -> s.rule?.let { child.appendChild(rule(it, s)) } }
                element.appendChild(child)
            }
            for (input in rule.inputs) {
                element.appendChild(when (input) {
                    is RuleInput.Text -> document.createElement(STRING).apply { setAttribute(VALUE, input.value) }
                    is RuleInput.Predicates -> document.createElement(PRED_REF).apply { setAttribute(REF, preds(input.values)) }
                    is RuleInput.Expressions -> document.createElement(EXPR_REF).apply {
                        setAttribute(REF, input.values.joinToString(",") { e -> e?.let { expr(it) } ?: "" })
                    }
                }.apply { setAttribute("name", "." + input.key) })
            }
            return element
        }

        private fun antecedent(index: Int, antecedent: Antecedent): Element {
            val element = document.createElement(ANTE)
            element.setAttribute("name", index.toString())
            antecedent.goal?.let { element.setAttribute(GOAL, pred(it)) }
            if (antecedent.added.isNotEmpty()) element.setAttribute(HYPS, preds(antecedent.added))
            if (antecedent.unselected.isNotEmpty()) element.setAttribute(UNSEL, preds(antecedent.unselected))
            antecedent.idents.forEach { element.appendChild(ident(it.name, it.type.toString())) }
            antecedent.actions.forEachIndexed { i, action ->
                element.appendChild(document.createElement(HYP_ACTION).apply {
                    setAttribute("name", action.type + i)
                    when (action) {
                        is HypAction.Selection -> setAttribute(HYPS, preds(action.hyps))
                        is HypAction.Inference -> {
                            setAttribute(HYPS, preds(action.hyps - action.hidden.toSet()))
                            if (action.type == HypAction.REWRITE) setAttribute(HIDDEN, preds(action.hidden))
                            setAttribute(INF_HYPS, preds(action.inferred))
                            action.idents.forEach { appendChild(ident(it.name, it.type.toString())) }
                        }
                    }
                })
            }
            return element
        }
    }
}
