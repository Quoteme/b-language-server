package b.language.server.analysis.prob.animation

import b.language.server.analysis.prob.ModelCheckOutcome
import b.language.server.analysis.prob.ProBPreferences
import b.language.server.analysis.prob.ProBSession
import b.language.server.analysis.prob.Transitions
import de.prob.animator.command.ExportVisBHtmlForStates
import de.prob.animator.command.FindStateCommand
import de.prob.animator.command.GetAnimationMatrixForStateCommand
import de.prob.animator.command.GetImagesForMachineCommand
import de.prob.animator.command.LoadVisBCommand
import de.prob.animator.command.ReadVisBPathFromDefinitionsCommand
import de.prob.animator.domainobjects.AbstractEvalResult
import de.prob.animator.domainobjects.AnimationMatrixEntry
import de.prob.animator.domainobjects.DotVisualizationCommand
import de.prob.animator.domainobjects.DynamicCommandItem
import de.prob.animator.domainobjects.EvalResult
import de.prob.animator.domainobjects.FormulaExpand
import de.prob.animator.domainobjects.TableVisualizationCommand
import de.prob.exception.ProBError
import de.prob.statespace.State
import de.prob.statespace.StateSpace
import de.prob.statespace.Trace
import de.prob.statespace.Transition
import java.io.File
import java.nio.file.Files

/**
 * An animation of a machine, like the views of ProB for Rodin: enabled events with their
 * parameters, the current state and the history, which can be stepped through.
 *
 * It runs in its own ProB process, so that checking files while animating does not disturb it.
 */
class Animation : AutoCloseable {
    /** Started with the first animation only. */
    private val started = lazy { ProBSession() }
    private val session by started

    private var model: AnimationModel? = null
    private var sources: AnimationSources? = null
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

    /** Loads [model] and starts a new animation of it. */
    fun start(model: AnimationModel): AnimationResponse {
        stop()
        this.model = model
        message = ""
        val stateSpace = session.createStateSpace(ProBPreferences(maxOperations = 20, maxInitialisations = 20))
        val loaded =
            try {
                model.load(session, stateSpace)
            } catch (e: ProBError) {
                AnimationLoad.Failed(e.errors.firstOrNull()?.message ?: e.message ?: "ProB could not load the model")
            }
        when (loaded) {
            is AnimationLoad.Failed -> {
                stateSpace.kill()
                return Failure(loaded.reason)
            }

            is AnimationLoad.Loaded -> {
                sources = loaded.sources
            }
        }
        trace = Trace(stateSpace)
        images =
            try {
                GetImagesForMachineCommand().also { stateSpace.execute(it) }.images.mapValues { (_, name) ->
                    File(name).let { if (it.isAbsolute) it else File(model.file.absoluteFile.parentFile, name) }
                }
            } catch (e: ProBError) {
                emptyMap()
            }
        visb = loadVisB(stateSpace, model.file)
        return view()
    }

    fun stop(): Stopped {
        trace?.stateSpace?.kill()
        trace = null
        sources = null
        graphicsPage = null
        visb = null
        visbPage = null
        return Stopped()
    }

    /** Stops the animation and ends its ProB process, if it was started. */
    override fun close() {
        stop()
        if (started.isInitialized()) session.close()
    }

    /** Runs one step of the animation and returns the new view. */
    fun perform(
        action: AnimationAction,
        argument: String?,
        count: Int,
    ): AnimationResponse {
        val current = trace ?: return Failure(NOT_RUNNING)
        message = ""
        try {
            trace =
                when (action) {
                    AnimationAction.EXECUTE -> {
                        current.nextTransitions.firstOrNull { it.id == argument }?.let { current.add(it) } ?: current
                    }

                    AnimationAction.EXECUTE_WITH -> {
                        val parts = (argument ?: "").split('\u0000', limit = 2)
                        val event = parts[0]
                        val predicate = parts.getOrElse(1) { "" }
                        val predicates = listOfNotNull(predicate.ifBlank { null })
                        if (current.canExecuteEvent(event, predicates)) {
                            current.execute(event, predicates)
                        } else {
                            message = "$event cannot be executed${if (predicate.isBlank()) "" else " with $predicate"}"
                            current
                        }
                    }

                    AnimationAction.BACK -> {
                        generateSequence(current) { if (it.canGoBack()) it.back() else null }.take(count + 1).last()
                    }

                    AnimationAction.FORWARD -> {
                        generateSequence(current) { if (it.canGoForward()) it.forward() else null }.take(count + 1).last()
                    }

                    AnimationAction.GOTO -> {
                        current.gotoPosition((argument?.toIntOrNull() ?: 0) - 1)
                    }

                    AnimationAction.RANDOM -> {
                        current.randomAnimation(count).also {
                            if (it.transitionList.size < current.transitionList.size + count) message = "Stopped early: no event is enabled"
                        }
                    }

                    AnimationAction.FIND -> {
                        find(current, argument ?: "")
                    }

                    AnimationAction.MODEL_CHECK -> {
                        modelCheck(current)
                    }

                    AnimationAction.RESTART -> {
                        return start(model ?: return Failure(NOT_RUNNING))
                    }

                    AnimationAction.START, AnimationAction.STOP, AnimationAction.VIEW -> {
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
        val outcome = ModelCheckOutcome.of(current.stateSpace, MODEL_CHECK_STATES)
        message = "Model check: ${outcome.message}"
        return if (outcome is ModelCheckOutcome.ErrorFound) outcome.trace else current
    }

    // ----------------------------------------------------------------------
    // Visualisations of ProB Tcl/Tk

    /**
     * Shows the current state: as a page that follows the animation (graphics, VisB), as the dot
     * visualisation [command] rendered as [format], or as the table visualisation [command].
     * [formulas] are the visualisation's arguments.
     */
    fun visualize(request: VisualizationRequest): VisualizationResponse {
        val trace = trace ?: return Failure(NOT_RUNNING)
        return try {
            when (request.kind) {
                VisualizationKind.GRAPHICS -> {
                    val page = File(output, "graphics.html")
                    graphicsPage = page
                    writeGraphics(trace)
                    Page(page.path)
                }

                VisualizationKind.VISB -> {
                    if (visb == null) return Failure("The machine has no VISB_JSON_FILE in its DEFINITIONS")
                    val page = File(output, "visb.html")
                    visbPage = page
                    writeVisB(trace)
                    Page(page.path)
                }

                VisualizationKind.DOT -> {
                    val format = request.format.ifBlank { "svg" }
                    val visualization = DotVisualizationCommand.getByName(request.command, trace)
                    val target = File(output, "${request.command}.$format")
                    target.writeBytes(visualization.visualizeToBytes(format, parse(trace, request.formulas)))
                    Page(target.path)
                }

                VisualizationKind.TABLE -> {
                    val visualization = TableVisualizationCommand.getByName(request.command, trace)
                    val table = visualization.visualize(parse(trace, request.formulas))
                    Table(visualization.name, table.header, table.rows)
                }
            }
        } catch (e: ProBError) {
            Failure(e.errors.firstOrNull()?.message ?: e.message ?: "ProB could not visualise the state")
        } catch (e: Exception) {
            Failure(e.message ?: e.toString())
        }
    }

    private fun parse(
        trace: Trace,
        formulas: List<String>,
    ) = formulas.map { trace.stateSpace.model.parseFormula(it, FormulaExpand.EXPAND) }

    /** The dot and table visualisations of ProB Tcl/Tk's Visualize menu for the current state. */
    private fun visualizations(trace: Trace): List<VisualizationEntry> =
        try {
            fun entry(
                kind: VisualizationKind,
                item: DynamicCommandItem,
            ) = VisualizationEntry(kind, item.command, item.name, item.description, item.arity, item.isAvailable, item.available)
            DotVisualizationCommand.getAll(trace).map { entry(VisualizationKind.DOT, it) } +
                TableVisualizationCommand.getAll(trace).map { entry(VisualizationKind.TABLE, it) }
        } catch (e: ProBError) {
            emptyList()
        }

    /**
     * The grid of ANIMATION_FUNCTION in [state], as ProB Tcl/Tk draws it: each cell has a text,
     * which for an image is ANIMATION_STR's or the image's name, and the image file if any.
     */
    private fun graphics(state: State): List<List<GraphicsCell>>? {
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
                        val image = images[entry.imageNumber]
                        GraphicsCell(image?.nameWithoutExtension ?: "#${entry.imageNumber}", image?.path)
                    }

                    is AnimationMatrixEntry.Text -> {
                        GraphicsCell(entry.text)
                    }

                    else -> {
                        GraphicsCell("")
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
            if (rows == null) {
                "<p>No graphical animation for this state: the machine needs ANIMATION_FUNCTION in its DEFINITIONS.</p>"
            } else {
                rows.joinToString("\n", "<table>\n", "\n</table>") { row ->
                    row.joinToString("", "<tr>", "</tr>") { cell ->
                        val image = cell.image?.let(::File)
                        if (image != null && image.exists()) {
                            "<td><img src=\"${escape(image.toURI().toString())}\" alt=\"${escape(cell.text)}\"></td>"
                        } else {
                            "<td>${escape(cell.text)}</td>"
                        }
                    }
                }
            }
        val name = model?.file?.nameWithoutExtension ?: ""
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

    private fun view(): AnimationResponse {
        val trace = trace ?: return Failure(NOT_RUNNING)
        val sources = sources ?: return Failure(NOT_RUNNING)
        val file = model?.file ?: return Failure(NOT_RUNNING)
        val stateSpace = trace.stateSpace
        val state = trace.currentState
        val previous = trace.previousState
        val machine = stateSpace.loadedMachine
        val context = sources.isContext
        // ProB reports no constants for a context loaded on its own, so Event-B names come from the checked model.
        val constantNames = sources.constants ?: machine.constantNames
        val variableNames = sources.variables ?: machine.variableNames

        val position = trace.currentElements.size - 1
        val history =
            trace.elements.drop(1).mapIndexed { index, element ->
                HistoryEntry(label(element.transition, context), index + 1, index + 1 > position)
            }

        fun values(
            names: List<String>,
            ready: Boolean,
            kind: DeclarationKind,
        ) = names.map { name ->
            val value = if (ready) value(state, name) else null
            val before = if (ready && previous != null && previous.isInitialised == state.isInitialised) value(previous, name) else value
            NamedValue(name, value, value != before, sources.declaration(name, kind))
        }

        val enabled =
            trace.getNextTransitions(true, FormulaExpand.TRUNCATE).sortedBy { label(it, context) }.map { transition ->
                val name = display(transition.name, context)
                EnabledEvent(transition.id, name, transition.name, parameters(transition, constantNames, variableNames, context), sources.event(name))
            }
        val enabledNames = enabled.map { it.event }.toSet()
        val disabled =
            if (!state.isInitialised) {
                emptyList()
            } else {
                machine.operationNames
                    .filter { it !in enabledNames && it != Transitions.INITIALISATION }
                    .sorted()
                    .map { name ->
                        DisabledEvent(name, sources.guards(name).map { GuardStatus(it.label, holds(state, it.formula)) }, sources.event(name))
                    }
            }

        val invariants =
            if (!state.isInitialised) {
                emptyList()
            } else {
                // A context has no invariants; its axioms hold for the chosen constants.
                val labelled = if (context) sources.axioms else sources.invariants
                if (labelled.isEmpty()) {
                    listOf(PredicateStatus("INVARIANT", state.isInvariantOk, sources.declaration("INVARIANT", DeclarationKind.INVARIANT_CLAUSE)))
                } else {
                    labelled.map { PredicateStatus(it.label, holds(state, it.formula), it.location) }
                }
            }

        writeGraphics(trace)
        writeVisB(trace)
        // A context's constants are set up by its only step.
        val constantsSetUp = if (context) state.isInitialised else state.isConstantsSetUp
        return AnimationView(
            machine = sources.name,
            file = file.toPath().toUri().toString(),
            step = position,
            history = history,
            canBack = trace.canGoBack(),
            canForward = trace.canGoForward(),
            initialised = state.isInitialised,
            constantsSetUp = constantsSetUp,
            constants = values(constantNames, constantsSetUp, DeclarationKind.CONSTANT),
            variables = values(variableNames, state.isInitialised, DeclarationKind.VARIABLE),
            invariants = invariants,
            enabled = enabled,
            disabled = disabled,
            deadlock = state.isInitialised && enabled.isEmpty() && !context,
            context = context,
            errors = state.stateErrors.map { "${it.event}: ${it.shortDescription}" },
            message = message,
            graphics = if (state.isInitialised) graphics(state) else null,
            visualizations = visualizations(trace),
            visb = visb != null,
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
                Transitions.SETUP_CONSTANTS -> constants
                Transitions.INITIALISE_MACHINE -> if (context) constants else variables
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
        Transitions.SETUP_CONSTANTS -> Transitions.SETUP_CONSTANTS_LABEL
        Transitions.INITIALISE_MACHINE -> if (context) Transitions.SETUP_CONSTANTS_LABEL else Transitions.INITIALISATION
        else -> name
    }

    private companion object {
        const val NOT_RUNNING = "No animation is running"
        const val MODEL_CHECK_STATES = 100000
    }
}
