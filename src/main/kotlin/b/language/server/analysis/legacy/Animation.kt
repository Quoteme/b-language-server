package b.language.server.analysis.legacy

import b.language.server.client.CommunicatorInterface
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Stage
import de.prob.animator.ReusableAnimator
import de.prob.animator.command.ExportVisBHtmlForStates
import de.prob.animator.command.FindStateCommand
import de.prob.animator.command.GetAnimationMatrixForStateCommand
import de.prob.animator.command.GetImagesForMachineCommand
import de.prob.animator.command.LoadVisBCommand
import de.prob.animator.command.ReadVisBPathFromDefinitionsCommand
import de.prob.animator.domainobjects.AbstractEvalResult
import de.prob.animator.domainobjects.AnimationMatrixEntry
import de.prob.animator.domainobjects.DotVisualizationCommand
import de.prob.animator.domainobjects.EvalResult
import de.prob.animator.domainobjects.FormulaExpand
import de.prob.animator.domainobjects.TableVisualizationCommand
import de.prob.check.ConsistencyChecker
import de.prob.check.ModelCheckErrorUncovered
import de.prob.check.ModelCheckingOptions
import de.prob.exception.ProBError
import de.prob.model.eventb.EventBModel
import de.prob.scripting.FactoryProvider
import de.prob.statespace.State
import de.prob.statespace.StateSpace
import de.prob.statespace.Trace
import de.prob.statespace.Transition
import java.io.File
import java.nio.file.Files
import java.time.Duration

/**
 * An animation of a machine, like the views of ProB for Rodin: enabled events with their
 * parameters, the current state and the history, which can be stepped through.
 *
 * It runs in its own ProB process, so that checking files while animating does not disturb it.
 */
class Animation(
    private val communicator: CommunicatorInterface,
) {
    /** A place in a source file, 0-based. */
    data class Location(
        val file: File,
        val line: Int,
        val character: Int = 0,
    )

    private val injector: Injector by lazy { Guice.createInjector(Stage.PRODUCTION, ProBKernelModule(communicator)) }
    private val animator: ReusableAnimator by lazy { injector.getInstance(ReusableAnimator::class.java) }

    private var file: File? = null
    private var project: CamilleProject? = null
    private var trace: Trace? = null
    private var message = ""

    /** The images of ANIMATION_IMGn, by number. */
    private var images: Map<Int, File> = emptyMap()

    /** The page showing the graphical animation, once opened; it is rewritten after every step. */
    private var graphicsPage: File? = null

    /** The VisB visualisation named by VISB_JSON_FILE, once loaded. */
    private var visb: File? = null

    /** The page showing the VisB visualisation, once opened; it is rewritten after every step. */
    private var visbPage: File? = null
    private val output: File by lazy { Files.createTempDirectory("prob-visualization").toFile().apply { deleteOnExit() } }

    /** Loads [file] and starts a new animation of it. */
    fun start(file: File): Map<String, Any?> {
        stop()
        this.file = file
        message = ""
        val stateSpace = animator.createStateSpace()
        try {
            stateSpace.changePreferences(mapOf("MAX_OPERATIONS" to "20", "MAX_INITIALISATIONS" to "20"))
            if (CamilleProject.isCamille(file.readText())) {
                val project = CamilleProject(file)
                val errors = project.check().filter { it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error }
                if (errors.isNotEmpty()) {
                    stateSpace.kill()
                    return failure("The model has errors; fix them first: ${errors.first().message}")
                }
                this.project = project
                val (model, main) = project.toModel(injector.getInstance(EventBModel::class.java))
                model.loadIntoStateSpace(stateSpace, main)
            } else {
                val factory = injector.getInstance(FactoryProvider.factoryClassFromExtension(file.extension))
                factory.extract(file.absolutePath).loadIntoStateSpace(stateSpace)
            }
        } catch (e: ProBError) {
            stateSpace.kill()
            return failure(e.errors.firstOrNull()?.message ?: e.message ?: "ProB could not load the model")
        }
        trace = Trace(stateSpace)
        images =
            try {
                GetImagesForMachineCommand().also { stateSpace.execute(it) }.images.mapValues { (_, name) ->
                    File(name).let { if (it.isAbsolute) it else File(file.absoluteFile.parentFile, name) }
                }
            } catch (e: ProBError) {
                emptyMap()
            }
        visb = loadVisB(stateSpace, file)
        return view()
    }

    fun stop() {
        trace?.stateSpace?.kill()
        trace = null
        project = null
        graphicsPage = null
        visb = null
        visbPage = null
    }

    /** Runs one action of the animation and returns the new view. */
    fun perform(
        action: String,
        argument: String?,
        count: Int,
    ): Map<String, Any?> {
        val current = trace ?: return failure("No animation is running")
        message = ""
        try {
            trace =
                when (action) {
                    "execute" -> {
                        current.nextTransitions.firstOrNull { it.id == argument }?.let { current.add(it) } ?: current
                    }

                    "executeWith" -> {
                        val (event, predicate) = (argument ?: "").split('\u0000', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                        if (current.canExecuteEvent(event, listOfNotNull(predicate.ifBlank { null }))) {
                            current.execute(event, listOfNotNull(predicate.ifBlank { null }))
                        } else {
                            message = "$event cannot be executed${if (predicate.isBlank()) "" else " with $predicate"}"
                            current
                        }
                    }

                    "back" -> {
                        generateSequence(current) { if (it.canGoBack()) it.back() else null }.take(count + 1).last()
                    }

                    "forward" -> {
                        generateSequence(current) { if (it.canGoForward()) it.forward() else null }.take(count + 1).last()
                    }

                    "goto" -> {
                        current.gotoPosition((argument?.toIntOrNull() ?: 0) - 1)
                    }

                    "random" -> {
                        current.randomAnimation(count).also {
                            if (it.transitionList.size < current.transitionList.size + count) message = "Stopped early: no event is enabled"
                        }
                    }

                    "find" -> {
                        find(current, argument ?: "")
                    }

                    "modelCheck" -> {
                        modelCheck(current)
                    }

                    "restart" -> {
                        return start(file ?: return failure("No animation is running"))
                    }

                    "view" -> {
                        current
                    }

                    else -> {
                        current
                    }
                }
        } catch (e: ProBError) {
            message = e.errors.firstOrNull()?.message ?: e.message ?: "ProB reported an error"
        } catch (e: IllegalArgumentException) {
            message = e.message ?: "Invalid input"
        }
        return view()
    }

    private fun find(
        current: Trace,
        predicate: String,
    ): Trace {
        val stateSpace = current.stateSpace
        val command = FindStateCommand(stateSpace, stateSpace.model.parseFormula(predicate, FormulaExpand.EXPAND), true)
        stateSpace.execute(command)
        return when (command.result) {
            FindStateCommand.ResultType.STATE_FOUND -> command.getTrace(stateSpace).also { message = "Found a state where $predicate" }
            else -> current.also { message = "No state found where $predicate" }
        }
    }

    private fun modelCheck(current: Trace): Trace {
        val stateSpace = current.stateSpace
        val options =
            ModelCheckingOptions()
                .checkDeadlocks(true)
                .checkInvariantViolations(true)
                .stateLimit(100000)
                .timeLimit(Duration.ofSeconds(60))
        val result = ConsistencyChecker(stateSpace, options).call()
        message = "Model check: ${result.message}"
        return if (result is ModelCheckErrorUncovered) result.getTrace(stateSpace) else current
    }

    private fun failure(text: String) = mapOf("error" to text)

    // ----------------------------------------------------------------------
    // Visualisations of ProB Tcl/Tk

    /**
     * Shows the current state: [kind] "graphics" writes the graphical animation as a page that
     * follows the animation, "dot" renders the dot visualisation [command] as [format], and
     * "table" returns the table visualisation [command]. [formulas] are its arguments.
     */
    fun visualize(
        kind: String,
        command: String,
        formulas: List<String>,
        format: String,
    ): Map<String, Any?> {
        val trace = trace ?: return failure("No animation is running")
        return try {
            when (kind) {
                "graphics" -> {
                    val page = File(output, "graphics.html")
                    graphicsPage = page
                    writeGraphics(trace)
                    mapOf("path" to page.path)
                }

                "visb" -> {
                    if (visb == null) return failure("The machine has no VISB_JSON_FILE in its DEFINITIONS")
                    visbPage = File(output, "visb.html")
                    writeVisB(trace)
                    mapOf("path" to visbPage!!.path)
                }

                "dot" -> {
                    val visualization = DotVisualizationCommand.getByName(command, trace)
                    val target = File(output, "$command.${format.ifBlank { "svg" }}")
                    target.writeBytes(visualization.visualizeToBytes(format.ifBlank { "svg" }, parse(trace, formulas)))
                    mapOf("path" to target.path)
                }

                "table" -> {
                    val visualization = TableVisualizationCommand.getByName(command, trace)
                    val table = visualization.visualize(parse(trace, formulas))
                    mapOf("title" to visualization.name, "header" to table.header, "rows" to table.rows)
                }

                else -> {
                    failure("Unknown visualisation $kind")
                }
            }
        } catch (e: ProBError) {
            failure(e.errors.firstOrNull()?.message ?: e.message ?: "ProB could not visualise the state")
        } catch (e: Exception) {
            failure(e.message ?: e.toString())
        }
    }

    private fun parse(
        trace: Trace,
        formulas: List<String>,
    ) = formulas.map { trace.stateSpace.model.parseFormula(it, FormulaExpand.EXPAND) }

    /** The dot and table visualisations of ProB Tcl/Tk's Visualize menu for the current state. */
    private fun visualizations(trace: Trace): List<Map<String, Any?>> =
        try {
            fun entry(
                kind: String,
                it: de.prob.animator.domainobjects.DynamicCommandItem,
            ) = mapOf(
                "kind" to kind,
                "command" to it.command,
                "name" to it.name,
                "description" to it.description,
                "arity" to it.arity,
                "available" to it.isAvailable,
                "reason" to it.available,
            )
            DotVisualizationCommand.getAll(trace).map { entry("dot", it) } +
                TableVisualizationCommand.getAll(trace).map { entry("table", it) }
        } catch (e: ProBError) {
            emptyList()
        }

    /**
     * The grid of ANIMATION_FUNCTION in [state], as ProB Tcl/Tk draws it: each cell has a text,
     * which for an image is ANIMATION_STR's or the image's name, and the image file if any.
     */
    private fun graphics(state: State): List<List<Map<String, Any?>>>? {
        val matrix =
            try {
                GetAnimationMatrixForStateCommand(state).also { state.stateSpace.execute(it) }.matrix
            } catch (e: ProBError) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
        if (matrix.isNullOrEmpty()) return null
        return matrix.map { row ->
            row.map { entry ->
                when (entry) {
                    is AnimationMatrixEntry.Image -> {
                        images[entry.imageNumber].let { image ->
                            mapOf("text" to (image?.nameWithoutExtension ?: "#${entry.imageNumber}"), "image" to image?.path)
                        }
                    }

                    is AnimationMatrixEntry.Text -> {
                        mapOf("text" to entry.text)
                    }

                    else -> {
                        mapOf("text" to "")
                    }
                }
            }
        }
    }

    /** Loads the VisB file that VISB_JSON_FILE names, relative to the model, as ProB Tcl/Tk does. */
    private fun loadVisB(
        stateSpace: StateSpace,
        file: File,
    ): File? =
        try {
            val path = ReadVisBPathFromDefinitionsCommand().also { stateSpace.execute(it) }.path
            path
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it).let { f -> if (f.isAbsolute) f else File(file.absoluteFile.parentFile, it) } }
                ?.also { stateSpace.execute(LoadVisBCommand(it.absolutePath)) }
        } catch (e: ProBError) {
            message = "VisB: " + (e.errors.firstOrNull()?.message ?: e.message)
            null
        }

    /** Writes the VisB visualisation of the current state to [visbPage], made to reload itself. */
    private fun writeVisB(trace: Trace) {
        val page = visbPage ?: return
        if (!trace.currentState.isInitialised) return
        try {
            trace.stateSpace.execute(ExportVisBHtmlForStates(trace.currentState, page.toPath()))
            page.writeText(page.readText().replaceFirst(Regex("(?i)<head>"), "<head><meta http-equiv=\"refresh\" content=\"1\">"))
        } catch (e: ProBError) {
            message = "VisB: " + (e.errors.firstOrNull()?.message ?: e.message)
        }
    }

    /** Writes the graphical animation of the current state to [graphicsPage], which reloads itself. */
    private fun writeGraphics(trace: Trace) {
        val page = graphicsPage ?: return

        fun escape(text: String) =
            text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        val rows = graphics(trace.currentState)
        val body =
            if (rows ==
                null
            ) {
                "<p>No graphical animation for this state: the machine needs ANIMATION_FUNCTION in its DEFINITIONS.</p>"
            } else {
                rows.joinToString("\n", "<table>\n", "\n</table>") { row ->
                    row.joinToString("", "<tr>", "</tr>") { cell ->
                        val image = cell["image"] as String?
                        if (image != null &&
                            File(image).exists()
                        ) {
                            "<td><img src=\"${escape(File(image).toURI().toString())}\" alt=\"${escape(cell["text"] as String)}\"></td>"
                        } else {
                            "<td>${escape(cell["text"] as String)}</td>"
                        }
                    }
                }
            }
        val name = file?.nameWithoutExtension ?: ""
        page.writeText(
            """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta http-equiv="refresh" content="1">
<title>ProB · ${escape(name)}</title>
<style>body{font-family:sans-serif}table{border-collapse:collapse}td{padding:0;text-align:center;font-family:monospace}img{display:block}</style>
</head><body>
<p>ProB · ${escape(name)} · step ${trace.currentElements.size - 1}</p>
$body
</body></html>
""",
        )
    }

    // ----------------------------------------------------------------------
    // The view sent to the client

    private fun view(): Map<String, Any?> {
        val trace = trace ?: return failure("No animation is running")
        val stateSpace = trace.stateSpace
        val state = trace.currentState
        val previous = trace.previousState
        val machine = stateSpace.loadedMachine
        val sources = Sources(file!!, project)
        // ProB reports no constants for a context loaded on its own, so Event-B names come from the checked model.
        val constantNames = sources.constants() ?: machine.constantNames
        val variableNames = sources.variables() ?: machine.variableNames

        val elements = trace.elements
        val position = trace.currentElements.size - 1
        val history =
            elements.drop(1).mapIndexed { index, element ->
                mapOf("label" to label(element.transition, sources.isContext), "index" to index + 1, "future" to (index + 1 > position))
            }

        fun values(
            names: List<String>,
            ready: Boolean,
            kind: String,
        ) = names.map { name ->
            val value = if (ready) value(state, name) else null
            val before = if (ready && previous != null && previous.isInitialised == state.isInitialised) value(previous, name) else value
            mapOf("name" to name, "value" to value, "changed" to (value != before)) + location(sources.declaration(name, kind))
        }

        val enabled =
            trace.getNextTransitions(true, FormulaExpand.TRUNCATE).sortedBy { label(it, sources.isContext) }.map { transition ->
                mapOf(
                    "id" to transition.id,
                    "name" to display(transition.name, sources.isContext),
                    "event" to transition.name,
                    "parameters" to parameters(transition, constantNames, variableNames, sources.isContext),
                ) + location(sources.event(display(transition.name, sources.isContext)))
            }
        val enabledNames = enabled.map { it["event"] }.toSet()
        val disabled =
            if (!state.isInitialised) {
                emptyList()
            } else {
                machine.operationNames
                    .filter {
                        it !in enabledNames &&
                            it != INITIALISATION
                    }.sorted()
                    .map { name ->
                        mapOf(
                            "name" to name,
                            "guards" to
                                sources.guards(name).map { (label, formula) ->
                                    mapOf("label" to label, "ok" to holds(state, formula))
                                },
                        ) + location(sources.event(name))
                    }
            }

        val invariants =
            if (!state.isInitialised) {
                emptyList()
            } else {
                // A context has no invariants; its axioms hold for the chosen constants.
                val labelled = if (sources.isContext) sources.axioms() else sources.invariants()
                if (labelled.isEmpty()) {
                    listOf(
                        mapOf("label" to "INVARIANT", "ok" to state.isInvariantOk) + location(sources.declaration("INVARIANT", "clause")),
                    )
                } else {
                    labelled.map { (label, formula, at) -> mapOf("label" to label, "ok" to holds(state, formula)) + location(at) }
                }
            }

        writeGraphics(trace)
        writeVisB(trace)
        return mapOf(
            "machine" to sources.name,
            "file" to file!!.toPath().toUri().toString(),
            "step" to position,
            "history" to history,
            "canBack" to trace.canGoBack(),
            "canForward" to trace.canGoForward(),
            "initialised" to state.isInitialised,
            // A context's constants are set up by its only step.
            "constantsSetUp" to (if (sources.isContext) state.isInitialised else state.isConstantsSetUp),
            "constants" to values(constantNames, if (sources.isContext) state.isInitialised else state.isConstantsSetUp, "constants"),
            "variables" to values(variableNames, state.isInitialised, "variables"),
            "invariants" to invariants,
            "enabled" to enabled,
            "disabled" to disabled,
            "deadlock" to (state.isInitialised && enabled.isEmpty() && !sources.isContext),
            "context" to sources.isContext,
            "errors" to state.stateErrors.map { "${it.event}: ${it.shortDescription}" },
            "message" to message,
            "graphics" to (if (state.isInitialised) graphics(state) else null),
            "visualizations" to visualizations(trace),
            "visb" to (visb != null),
        )
    }

    private fun location(at: Location?): Map<String, Any?> =
        if (at == null) {
            emptyMap()
        } else {
            mapOf(
                "uri" to
                    at.file
                        .toPath()
                        .toUri()
                        .toString(),
                "line" to at.line,
                "character" to at.character,
            )
        }

    private fun value(
        state: State,
        formula: String,
    ): String? =
        when (val result: AbstractEvalResult = state.eval(formula, FormulaExpand.TRUNCATE)) {
            is EvalResult -> result.value
            else -> null
        }

    private fun holds(
        state: State,
        formula: String,
    ): Boolean? =
        when (value(state, formula)) {
            "TRUE" -> true
            "FALSE" -> false
            else -> null
        }

    /** The parameters of [transition]; for the setup and initialisation, the values they choose. */
    private fun parameters(
        transition: Transition,
        constants: List<String>,
        variables: List<String>,
        context: Boolean,
    ): String {
        val chosen =
            when (transition.name) {
                "\$setup_constants" -> constants
                "\$initialise_machine" -> if (context) constants else variables
                else -> null
            }
        if (chosen != null) {
            return chosen.joinToString(", ") { "$it = ${value(transition.destination, it) ?: "?"}" }
        }
        val names = transition.parameterNames
        val values = transition.parameterValues
        return if (names.size == values.size && names.isNotEmpty()) {
            names.zip(values).joinToString(", ") { (n, v) -> "$n = $v" }
        } else {
            values.joinToString(", ")
        }
    }

    private fun label(
        transition: Transition?,
        context: Boolean,
    ): String {
        if (transition == null) return ""
        val parameters = transition.parameterValues
        return display(transition.name, context) + if (parameters.isEmpty()) "" else "(${parameters.joinToString(", ")})"
    }

    /** ProB sets up the constants of a context on its own with its initialisation. */
    private fun display(
        name: String,
        context: Boolean,
    ) = when (name) {
        "\$setup_constants" -> "SETUP_CONSTANTS"
        "\$initialise_machine" -> if (context) "SETUP_CONSTANTS" else INITIALISATION
        else -> name
    }

    /** Where things are declared, from the checked Event-B model or by searching classical B text. */
    private class Sources(
        private val file: File,
        private val project: CamilleProject?,
    ) {
        private val machine = project?.mainInfo as? MachineInfo
        private val lines by lazy { file.readLines() }

        private val context = project?.mainInfo as? ContextInfo

        val name: String = machine?.name ?: context?.name ?: file.nameWithoutExtension

        val isContext = context != null

        private fun contexts(): List<ContextInfo>? = machine?.contexts ?: context?.closure()?.toList()

        fun constants(): List<String>? = contexts()?.flatMap { it.constants }

        fun variables(): List<String>? = if (project == null) null else machine?.allVariables?.toList().orEmpty()

        fun axioms(): List<Triple<String, String, Location>> =
            contexts().orEmpty().flatMap { c ->
                c.axioms.filter { !it.theorem }.map {
                    Triple(
                        it.label,
                        it.formula.toString(),
                        Location(
                            c.component.file,
                            it.token.line - 1,
                            it.token.pos - 1,
                        ),
                    )
                }
            }

        fun event(name: String): Location? {
            machine?.let { m -> m.events[name]?.let { return Location(m.component.file, it.token.line - 1, it.token.pos - 1) } }
            if (project != null) return null
            return search(Regex("""^\s*(?:[\w,\s]+<--\s*)?${Regex.escape(name)}\s*(\(|=)"""), "OPERATIONS")
        }

        fun guards(event: String): List<Pair<String, String>> {
            val info = machine?.events?.get(event) ?: return emptyList()
            if (info.parameters.isNotEmpty()) return emptyList()
            return info.guards.filter { !it.theorem }.map { it.label to it.formula.toString() }
        }

        fun invariants(): List<Triple<String, String, Location>> =
            machine
                ?.invariants
                ?.filter { !it.theorem }
                ?.map {
                    Triple(it.label, it.formula.toString(), Location(machine.component.file, it.token.line - 1, it.token.pos - 1))
                }.orEmpty()

        fun declaration(
            name: String,
            kind: String,
        ): Location? {
            if (project != null) {
                val files =
                    (
                        machine?.let { listOf(it.component.file) }.orEmpty() +
                            (machine?.contexts ?: (project.mainInfo as? ContextInfo)?.closure()?.toList().orEmpty()).map {
                                it.component.file
                            }
                    ).distinct()
                for (source in files) {
                    found(
                        source.readLines(),
                        Regex("""(?<![\w'])${Regex.escape(name)}(?![\w'])"""),
                        kind,
                    )?.let { return Location(source, it.first, it.second) }
                }
                return null
            }
            val clause =
                when (kind) {
                    "variables" -> "VARIABLES"
                    "constants" -> "CONSTANTS"
                    else -> name
                }
            return search(Regex("""(?<![\w'])${Regex.escape(name)}(?![\w'])"""), clause)
        }

        private fun search(
            pattern: Regex,
            clause: String,
        ): Location? = found(lines, pattern, clause)?.let { Location(file, it.first, it.second) }

        /** The first match of [pattern] at or after the line that starts [clause]. */
        private fun found(
            lines: List<String>,
            pattern: Regex,
            clause: String,
        ): Pair<Int, Int>? {
            val keyword = Regex("""(?i)^\s*(?:ABSTRACT_|CONCRETE_)?${Regex.escape(clause)}\b""")
            val start = lines.indexOfFirst { keyword.containsMatchIn(it) }
            if (start < 0) return null
            for (index in start until lines.size) {
                // The clause keyword itself is not a declaration.
                val line =
                    if (index != start) {
                        lines[index]
                    } else {
                        lines[index].let { text ->
                            keyword.find(text)?.let { text.replaceRange(it.range, " ".repeat(it.value.length)) } ?: text
                        }
                    }
                pattern.find(line)?.let { return index to it.range.first }
            }
            return null
        }
    }
}
