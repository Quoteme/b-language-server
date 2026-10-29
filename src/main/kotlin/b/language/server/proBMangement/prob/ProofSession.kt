package b.language.server.proBMangement.prob

import b.language.server.communication.CommunicatorInterface
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Stage
import de.prob.animator.ReusableAnimator
import org.eventb.core.ast.FormulaFactory
import org.eventb.core.ast.IPosition
import org.eventb.core.ast.Predicate
import java.io.File

/**
 * Interactive proof of one obligation at a time, like Rodin's Proving perspective: the proof
 * tree, the hypotheses and goal of the current node, and the tactics applying to them.
 *
 * It runs ProB in its own process, so that checking files while proving does not disturb it.
 * Every change is saved to the component's .bpr file at once.
 */
class ProofSession(private val communicator: CommunicatorInterface) {

    private val injector: Injector by lazy { Guice.createInjector(Stage.PRODUCTION, ProBKernelModule(communicator)) }
    private val animator: ReusableAnimator by lazy { injector.getInstance(ReusableAnimator::class.java) }
    private val factory = FormulaFactory.getDefault()

    private var file: File? = null
    private var obligations: List<ProofObligation> = emptyList()
    private var tree: ProofTree? = null
    private var current: ProofNode? = null
    private var message = ""
    private var prover: ProBProver? = null
    private var timeout = 10000

    /**
     * Opens the obligation [name] of [file], or the first one not proved yet. [obligations] are
     * those of the file's last check.
     */
    fun open(file: File, obligations: List<ProofObligation>, name: String?, timeout: Int): Map<String, Any?> {
        if (this.file != file) closeProver()
        this.file = file
        this.obligations = obligations
        this.timeout = timeout
        message = ""
        if (obligations.isEmpty()) {
            tree = null
            current = null
            return failure("${file.name} has no proof obligations; save it, and fix its errors first")
        }
        val po = obligations.firstOrNull { it.name == name } ?: obligations.firstOrNull { !it.closed } ?: obligations.first()
        if (name != null && po.name != name) message = "$name is not a proof obligation of ${file.name}"
        val tree = ProofTree(po)
        ProofStore(file).load()[po.name]?.let { tree.load(it) }
        this.tree = tree
        current = tree.root.openLeaves().firstOrNull() ?: tree.root
        return view()
    }

    /** The name of the obligation at [line] (0-based) of [file], for following the cursor. */
    fun at(file: File, obligations: List<ProofObligation>, line: Int): Map<String, Any?> {
        val text = file.takeIf { it.isFile }?.readLines()?.getOrNull(line).orEmpty()
        val labels = Regex("@([\\w'.]+)").findAll(text).map { it.groupValues[1] }.toList()
        val candidates = obligations.filter { po ->
            val parts = po.name.split("/")
            labels.any { it in parts } || po.line == line + 1
        }
        val po = candidates.firstOrNull { !it.closed } ?: candidates.firstOrNull()
        return mapOf("name" to po?.name)
    }

    fun close() {
        closeProver()
        file = null
        tree = null
        current = null
        obligations = emptyList()
    }

    private fun closeProver() {
        prover?.close()
        prover = null
    }

    /** Runs an action of the panel; see [view] for the answer. */
    fun perform(action: String, node: String?, tactic: String?, hyp: Int?, position: String?, input: String?): Map<String, Any?> {
        val tree = tree ?: return failure("no proof obligation is open")
        val target = node?.let { tree.root.find(it) } ?: current ?: tree.root
        message = ""
        try {
            when (action) {
                "view" -> {}
                "goto" -> current = target
                "apply" -> {
                    val chosen = Tactics.named(tactic ?: throw TacticException("no tactic given"))
                    val predicate = hyp?.let { i -> target.sequent.hyps.keys.elementAtOrNull(i) ?: throw TacticException("no hypothesis $i") }
                    val at = position?.takeIf { it.isNotEmpty() }?.let { FormulaFactory.makePosition(it) } ?: IPosition.ROOT
                    step(tree, target, chosen.make(target.sequent, Target(predicate, at), input))
                }
                "prob" -> prove(tree, target.openLeaves())
                "proball" -> prove(tree, tree.root.openLeaves())
                "review" -> step(tree, target, Tactics.review(target.sequent))
                "prune" -> {
                    tree.remember()
                    target.prune()
                    current = target
                    changed(tree)
                }
                "undo", "redo" -> {
                    val path = current?.path
                    if (if (action == "undo") tree.undo() else tree.redo()) {
                        current = path?.let { tree.root.find(it) } ?: tree.root.openLeaves().firstOrNull() ?: tree.root
                        changed(tree)
                    } else message = "nothing to $action"
                }
                else -> message = "unknown action $action"
            }
        } catch (e: TacticException) {
            message = e.message.orEmpty()
        }
        return view()
    }

    /** Applies [rule] at [node], closes what it can of the new leaves and saves. */
    private fun step(tree: ProofTree, node: ProofNode, rule: Rule) {
        tree.remember()
        if (!node.apply(rule)) throw TacticException("${rule.display} does not apply here")
        postTactic(node)
        current = node.openLeaves().firstOrNull() ?: tree.root.openLeaves().firstOrNull() ?: node
        changed(tree)
    }

    /** Closes the open leaves below [node] that need no choice, as Rodin's post-tactic. */
    private fun postTactic(node: ProofNode) {
        for (leaf in node.openLeaves()) Tactics.close(leaf.sequent)?.let { leaf.apply(it) }
    }

    /** Tries to close [leaves] with ProB. */
    private fun prove(tree: ProofTree, leaves: List<ProofNode>) {
        val open = leaves.filter { it.isOpen }
        if (open.isEmpty()) {
            message = "no open goal"
            return
        }
        val prover = prover ?: ProBProver(injector, animator, carrierSets(), timeout).also { prover = it }
        val proved = mutableListOf<ProofNode>()
        val failures = mutableListOf<String>()
        for (leaf in open) {
            val hyps = leaf.sequent.hyps.keys.toList()
            when (val result = prover.solve(ProBText.negation(leaf.sequent.env, hyps, leaf.sequent.goal))) {
                is ProBProver.Result.Proved -> proved += leaf
                is ProBProver.Result.Counterexample -> failures += "counterexample: ${result.detail}"
                is ProBProver.Result.Unknown -> failures += "ProB cannot decide: ${result.reason}"
            }
        }
        message = when {
            open.size == 1 -> if (proved.isNotEmpty()) "ProB proved the goal" else failures.first()
            else -> "ProB proved ${proved.size} of ${open.size} goals" + (failures.firstOrNull()?.let { "; $it" } ?: "")
        }
        if (proved.isEmpty()) return
        tree.remember()
        proved.forEach { it.apply(Tactics.prob(it.sequent, it.sequent.hyps.keys)) }
        current = tree.root.openLeaves().firstOrNull() ?: current
        changed(tree)
    }

    private fun carrierSets(): List<String> {
        val project = CamilleProject(file!!)
        project.check()
        return ProBProver.carrierSets(project)
    }

    /** Saves the proof and updates the obligation's status. */
    private fun changed(tree: ProofTree) {
        ProofStore(file!!).save(tree)
        tree.po.updateStatus(tree)
    }

    private fun failure(text: String) = mapOf("error" to text)

    /** The obligation being proved. */
    val obligation: ProofObligation? get() = tree?.po

    // ----------------------------------------------------------------------
    // The view sent to the client

    /**
     * What the panel shows: the obligations, the proof tree, and the current node's hypotheses
     * and goal. Each predicate lists the tactics applying to it, by character range of the text
     * (the operator of a sub-formula, or the whole predicate), as Rodin marks operators in red.
     */
    fun view(): Map<String, Any?> {
        val tree = tree ?: return failure("no proof obligation is open")
        val node = current ?: tree.root
        val po = tree.po
        val origins = po.hypotheses.associate { it.predicate to it.origin }
        return mapOf(
            "uri" to file!!.toURI().toString(),
            "name" to po.name,
            "status" to po.status.name.lowercase(),
            "detail" to po.detail,
            "line" to po.line - 1,
            "complete" to tree.complete,
            "obligations" to obligations.map { mapOf("name" to it.name, "status" to it.status.name.lowercase()) },
            "tree" to tree.root.nodes().map { n ->
                mapOf(
                    "path" to n.path,
                    "depth" to n.path.count { it == '.' } + if (n.path.isEmpty()) 0 else 1,
                    "display" to (n.rule?.display ?: n.broken?.rule?.let { "broken: ${it.display}" } ?: "…"),
                    "open" to n.isOpen,
                    "closed" to n.isClosed,
                    "broken" to (n.broken != null),
                    "current" to (n === node),
                )
            },
            "node" to node.path,
            "open" to node.isOpen,
            "hyps" to node.sequent.hyps.entries.mapIndexed { i, (p, state) ->
                predicate(p, false) + mapOf("id" to i, "origin" to origins[p], "state" to state.name.lowercase())
            },
            "goal" to predicate(node.sequent.goal, true),
            "message" to message,
        )
    }

    /** A predicate's text and the tactics at its operators. */
    private fun predicate(p: Predicate, goal: Boolean): Map<String, Any?> {
        val text = p.toString()
        val reparsed = factory.parsePredicate(text, null).takeUnless { it.hasProblem() }?.parsedPredicate
        val whole = mutableListOf<Map<String, Any?>>()
        val operators = LinkedHashMap<Pair<Int, Int>, MutableList<Map<String, Any?>>>()
        for ((tactic, position) in Tactics.applicable(p, goal)) {
            val entry = mapOf(
                "name" to tactic.name,
                "label" to tactic.label,
                "position" to position.toString(),
                "input" to tactic.input,
            )
            val range = if (tactic.positional) reparsed?.let { operatorRange(text, it, position) } else null
            if (range == null) whole += entry else {
                operators.getOrPut(range) { mutableListOf() } += entry
                // Rewrites are also listed for the whole predicate, with the sub-formula they rewrite.
                whole += entry + ("label" to "${tactic.label} in ${reparsed?.getSubFormula(position)}")
            }
        }
        return mapOf(
            "text" to text,
            "tactics" to whole,
            "operators" to operators.map { (range, tactics) -> mapOf("start" to range.first, "end" to range.second, "tactics" to tactics) },
        )
    }

    /** The characters of [text] showing the operator of the sub-formula at [position]: [start, end). */
    private fun operatorRange(text: String, parsed: Predicate, position: IPosition): Pair<Int, Int>? {
        val sub = parsed.getSubFormula(position) ?: return null
        val location = sub.sourceLocation ?: return null
        val children = (0 until sub.childCount).mapNotNull { sub.getChild(it).sourceLocation }
        val (from, to) = when {
            children.size >= 2 -> children[children.size - 2].end + 1 to children.last().start
            children.size == 1 && children[0].start > location.start -> location.start to children[0].start
            else -> location.start to location.end + 1
        }
        if (from >= to || to > text.length) return location.start to minOf(location.end + 1, text.length)
        var start = from
        var end = to
        while (start < end && (text[start] == ' ' || text[start] == ')')) start++
        while (end > start && (text[end - 1] == ' ' || text[end - 1] == '(')) end--
        return if (start < end) start to end else from to to
    }
}
