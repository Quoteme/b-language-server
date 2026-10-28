package b.language.server.proBMangement.prob


import b.language.server.communication.CommunicatorInterface
import b.language.server.dataStorage.ProBSettings
import com.google.inject.Inject
import com.google.inject.Injector
import de.prob.animator.ReusableAnimator
import de.prob.animator.command.CheckWellDefinednessCommand
import de.prob.animator.domainobjects.ErrorItem
import de.prob.exception.ProBError
import de.prob.scripting.FactoryProvider
import de.be4.eventb.core.parser.node.AMachineParseUnit
import de.prob.animator.command.CbcSolveCommand
import de.prob.animator.command.FormulaTypecheckCommand
import de.prob.animator.command.GetMachineIdentifiersCommand
import de.prob.animator.domainobjects.ComputationNotCompletedResult
import de.prob.animator.domainobjects.EvalResult
import de.prob.animator.domainobjects.EventB
import de.prob.animator.domainobjects.FormulaExpand
import de.prob.check.ConsistencyChecker
import de.prob.check.ModelCheckErrorUncovered
import de.prob.check.ModelCheckingOptions
import de.prob.model.eventb.Context
import de.prob.model.eventb.EventBModel
import de.prob.model.representation.ModelElementList
import java.time.Duration
import de.prob.model.representation.Set as ProBSet
import de.prob.statespace.AnimationSelector
import de.prob.statespace.StateSpace
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.MessageType
import java.io.File
import java.io.IOException
import java.lang.Exception

/**
 * Represents the interface to communicate with prob kernel
 * Is called via injector
 * @see ProBKernelManager
 */
class ProBKernel @Inject constructor(private val injector : Injector,
                                     private val animationSelector: AnimationSelector,
                                     private val animator : ReusableAnimator,
                                     private val communicator : CommunicatorInterface) {


    /**
     * Checks the given machine file; prepares all necessary object
     * @param file the file to check
     * @param settings the settings under which the check takes place
     * @return a list with the problems found
     */
    fun check(file : File, settings : ProBSettings) : List<Diagnostic>{
        communicator.sendDebugMessage("unloading old machine", MessageType.Info)
        unloadMachine()

        // Event-B in Camille notation shares .mch with classical B; its lowercase keywords tell them apart.
        if (CamilleProject.isCamille(file.readText())) {
            return checkCamille(file, settings)
        }

        val factory = injector.getInstance(FactoryProvider.factoryClassFromExtension(file.absolutePath.substringAfterLast(".")))

        val informationListener = InformationListener(file, communicator)
        animator.addWarningListener(informationListener)

        communicator.sendDebugMessage("loading new machine", MessageType.Info)

        val problems = loadMachine(settings, file, { stateSpace -> identifiers[file.absolutePath] = describeIdentifiers(stateSpace) }) { stateSpace ->
            factory.extract(file.absolutePath).loadIntoStateSpace(stateSpace)
        }

        communicator.sendDebugMessage("returning from kernel problems are $problems", MessageType.Info)

        return listOf(informationListener.getInformation(), problems).flatten()
    }

    /** The proof obligations of the Event-B components checked last, by file. */
    private val obligations = HashMap<String, List<ProofObligation>>()

    /**
     * Checks an Event-B component in Camille notation like Rodin does: static checks,
     * then proof obligations, which ProB tries to discharge or disprove
     * @param file the machine or context to check
     * @param settings the settings under which the check takes place
     * @return a list with the problems found
     */
    private fun checkCamille(file : File, settings : ProBSettings) : List<Diagnostic> {
        val project = CamilleProject(file)
        val problems = project.check()
        obligations.remove(file.absolutePath)
        if (problems.any { it.severity == DiagnosticSeverity.Error }) {
            return problems
        }

        val generated = ProofObligationGenerator().generate(project.mainInfo)
        communicator.sendDebugMessage("discharging ${generated.size} proof obligations", MessageType.Info)
        discharge(project, generated, settings.poTimeout)
        obligations[file.absolutePath] = generated
        return problems + generated.mapNotNull { it.toDiagnostic() }
    }

    /** Hover texts for the identifiers of classical B machines, by file and name, from their last check. */
    private val identifiers = HashMap<String, Map<String, String>>()

    fun identifiers(file : File) : Map<String, String> = identifiers[file.absolutePath].orEmpty()

    /** Describes every identifier of the loaded classical B machine as ProB sees it, with its type. */
    private fun describeIdentifiers(stateSpace : StateSpace) : Map<String, String> {
        val result = LinkedHashMap<String, String>()
        fun names(category : GetMachineIdentifiersCommand.Category) =
            GetMachineIdentifiersCommand(category).also { stateSpace.execute(it) }.identifiers
        fun type(name : String) : String? = try {
            FormulaTypecheckCommand(stateSpace.model.parseFormula(name, FormulaExpand.TRUNCATE)).also { stateSpace.execute(it) }
                .result.takeIf { it.isOk }?.type
        } catch (e : RuntimeException) {
            null
        }
        fun typed(name : String) = type(name)?.let { "$name : $it" } ?: name
        fun card(signature : String, text : String, link : String) = "```b\n$signature\n```\n\n---\n\n$text\n\n$link"
        val syntax = "https://prob.hhu.de/w/index.php?title=Summary_of_B_Syntax#Machine_sections"
        val sections = "[ProB, Summary of B Syntax: Machine sections]($syntax)"
        val sets = names(GetMachineIdentifiersCommand.Category.SETS)
        val elements = names(GetMachineIdentifiersCommand.Category.SET_CONSTANTS)
        for (name in sets) {
            val members = elements.filter { type(it) == name }
            result[name] = card(if (members.isEmpty()) "(deferred set) $name" else "(enumerated set) $name = {${members.joinToString(", ")}}",
                if (members.isEmpty()) "A deferred set, declared in SETS: a non-empty set whose elements are not given." else "An enumerated set, declared in SETS.", sections)
        }
        for (name in elements) {
            result[name] = card("(element) ${typed(name)}", "An element of an enumerated set.", sections)
        }
        for (name in names(GetMachineIdentifiersCommand.Category.CONSTANTS)) {
            result[name] = card("(constant) ${typed(name)}", "A constant; its type follows from PROPERTIES.", sections)
        }
        for (name in names(GetMachineIdentifiersCommand.Category.VARIABLES)) {
            result[name] = card("(variable) ${typed(name)}", "A variable; its type follows from INVARIANT.", sections)
        }
        for (name in names(GetMachineIdentifiersCommand.Category.FREETYPES)) {
            result[name] = card("(free type) $name", "A free type, declared in FREETYPES.", "[ProB, Summary of B Syntax: Free Types](https://prob.hhu.de/w/index.php?title=Summary_of_B_Syntax#Free_Types)")
        }
        for (name in names(GetMachineIdentifiersCommand.Category.DEFINITIONS)) {
            result.putIfAbsent(name, card("(definition) $name", "A definition, expanded where it is used.", "[ProB, Summary of B Syntax: Definitions](https://prob.hhu.de/w/index.php?title=Summary_of_B_Syntax#Definitions)"))
        }
        val machine = stateSpace.loadedMachine
        for (name in names(GetMachineIdentifiersCommand.Category.OPERATIONS)) {
            val info = machine.getMachineOperationInfo(name) ?: continue
            fun list(names : List<String>) = names.joinToString(", ") { p -> info.typeMap[p]?.let { "$p : ${prologType(it)}" } ?: p }
            val signature = (if (info.outputParameterNames.isEmpty()) "" else "${list(info.outputParameterNames)} <-- ") +
                name + (if (info.parameterNames.isEmpty()) "" else "(${list(info.parameterNames)})")
            val lines = mutableListOf("An operation of the machine.")
            if (info.readVariables.isNotEmpty()) lines += "Reads: " + info.readVariables.joinToString(", ") { "`$it`" }
            if (info.writtenVariables.isNotEmpty()) lines += "Writes: " + info.writtenVariables.joinToString(", ") { "`$it`" }
            result[name] = card("(operation) $signature", lines.joinToString("  \n"), sections)
        }
        return result
    }

    /** ProB's internal notation of a type, such as set(couple(integer,global('S'))), in B: POW(INTEGER*S). */
    private fun prologType(type : String) : String {
        var result = type
        result = result.replace(Regex("""global\('?([^')]+)'?\)"""), "$1")
        result = result.replace("integer", "INTEGER").replace("boolean", "BOOL").replace("string", "STRING").replace("real", "REAL")
        repeat(8) {
            result = result.replace(Regex("""couple\(([^()]*(?:\([^()]*\))*[^()]*),([^()]*(?:\([^()]*\))*[^()]*)\)""")) { "(${it.groupValues[1]}*${it.groupValues[2]})" }
            result = result.replace(Regex("""set\(([^()]*(?:\([^()]*\))*[^()]*)\)""")) { "POW(${it.groupValues[1]})" }
            result = result.replace(Regex("""seq\(([^()]*(?:\([^()]*\))*[^()]*)\)""")) { "seq(${it.groupValues[1]})" }
        }
        return result
    }

    /** The proof obligations of [file] from its last check. */
    fun proofObligations(file : File) : List<ProofObligation> = obligations[file.absolutePath].orEmpty()

    /**
     * Searches each obligation's negation for a solution with ProB's constraint solver: none means the
     * obligation is discharged, a solution is a counterexample.
     */
    private fun discharge(project : CamilleProject, obligations : List<ProofObligation>, timeout : Int) {
        if (obligations.isEmpty()) return
        val noExtensions = emptySet<org.eventb.core.ast.extension.IFormulaExtension>()
        val contexts = when (val info = project.mainInfo) {
            is MachineInfo -> info.contexts
            is ContextInfo -> info.closure().toList()
            else -> emptyList()
        }
        // The obligations mention constants and variables freely; only the carrier sets must be known.
        val sets = Context("proof_obligations").withSets(ModelElementList(contexts.flatMap { it.sets }.distinct().map { ProBSet(EventB(it, noExtensions)) }))
        val stateSpace = animator.createStateSpace()
        try {
            stateSpace.changePreferences(mapOf("TIME_OUT" to timeout.toString()))
            injector.getInstance(EventBModel::class.java).addContext(sets).loadIntoStateSpace(stateSpace, sets)
            for (obligation in obligations) {
                solve(stateSpace, obligation, noExtensions)
            }
        } catch (e : ProBError) {
            communicator.sendDebugMessage("could not discharge proof obligations: ${e.message}", MessageType.Info)
        } finally {
            stateSpace.kill()
        }
    }

    private fun solve(stateSpace : StateSpace, obligation : ProofObligation, noExtensions : Set<org.eventb.core.ast.extension.IFormulaExtension>) {
        for (solver in listOf(CbcSolveCommand.Solvers.PROB, CbcSolveCommand.Solvers.Z3AXM)) {
            val result = try {
                val command = CbcSolveCommand(EventB(obligation.negation, noExtensions, FormulaExpand.EXPAND), solver)
                stateSpace.execute(command)
                command.value
            } catch (e : ProBError) {
                obligation.detail = e.errors.firstOrNull()?.message ?: e.message?.lineSequence()?.firstOrNull().orEmpty()
                continue
            }
            if (result is EvalResult && result.value == "FALSE") {
                obligation.status = ProofObligation.Status.DISCHARGED
                return
            }
            if (result is EvalResult && result.value == "TRUE") {
                obligation.status = ProofObligation.Status.DISPROVED
                obligation.detail = result.solutions.entries.joinToString(", ") { (name, value) ->
                    "${name.replace(Regex("$PRIME_SUFFIX$"), "'")} = $value"
                }
                return
            }
            obligation.detail = (result as? ComputationNotCompletedResult)?.reason ?: result.toString()
        }
    }

    private fun ProofObligation.toDiagnostic() : Diagnostic? = when (status) {
        ProofObligation.Status.DISCHARGED -> null
        ProofObligation.Status.DISPROVED ->
            diagnostic(file, line, pos, length, if (detail.isEmpty()) "$name does not hold" else "$name does not hold, e.g. for $detail", DiagnosticSeverity.Warning)
        ProofObligation.Status.UNDISCHARGED ->
            diagnostic(file, line, pos, length, "$name is not discharged by ProB: $detail", DiagnosticSeverity.Hint)
    }

    /**
     * Model checks the machine in [file] with ProB for deadlocks and invariant violations
     * @return the problems found, and a summary of the run
     */
    fun modelCheck(file : File, settings : ProBSettings) : Pair<List<Diagnostic>, String> {
        unloadMachine()
        val camille = CamilleProject.isCamille(file.readText())
        val project = CamilleProject(file).takeIf { camille }
        if (project != null && project.check().any { it.severity == DiagnosticSeverity.Error }) {
            return emptyList<Diagnostic>() to "Model check skipped: the model has errors"
        }

        val stateSpace = animator.createStateSpace()
        try {
            stateSpace.changePreferences(prepareAdditionalSettings(settings))
            if (project != null) {
                val (model, mainComponent) = project.toModel(injector.getInstance(EventBModel::class.java))
                model.loadIntoStateSpace(stateSpace, mainComponent)
            } else {
                val factory = injector.getInstance(FactoryProvider.factoryClassFromExtension(file.extension))
                factory.extract(file.absolutePath).loadIntoStateSpace(stateSpace)
            }
            val options = ModelCheckingOptions().checkDeadlocks(true).checkInvariantViolations(true)
                .stateLimit(settings.modelCheckStates).timeLimit(Duration.ofSeconds(60))
            val result = ConsistencyChecker(stateSpace, options).call()
            if (result !is ModelCheckErrorUncovered) {
                return emptyList<Diagnostic>() to "Model check: ${result.message}"
            }

            val trace = result.getTrace(stateSpace)
            val path = trace.transitionList.map { it.name }
                .filter { it != "\$setup_constants" }
                .map { if (it == "\$initialise_machine") INITIALISATION else it }
            val after = if (path.isEmpty()) "" else " after ${path.joinToString(", ")}"
            val machine = project?.mainInfo as? MachineInfo
            val diagnostics = mutableListOf<Diagnostic>()
            if (result.message.contains("invariant", ignoreCase = true)) {
                val state = trace.currentState
                val violated = machine?.invariants.orEmpty().filter { invariant ->
                    (state.eval(EventB(invariant.formula.toString(), emptySet<org.eventb.core.ast.extension.IFormulaExtension>())) as? EvalResult)?.value == "FALSE"
                }
                violated.forEach { invariant ->
                    diagnostics += diagnostic(machine!!.component.file, invariant.token.line, invariant.token.pos, invariant.token.text.length,
                        "Invariant '${invariant.label}' violated$after", DiagnosticSeverity.Error)
                }
                if (violated.isEmpty()) diagnostics += diagnostic(file, 1, 1, 1, "Invariant violated$after", DiagnosticSeverity.Error)
            } else {
                val name = (machine?.component?.unit as? AMachineParseUnit)?.name
                val message = if (result.message.contains("deadlock", ignoreCase = true)) "Deadlock$after" else "${result.message}$after"
                diagnostics += if (name != null) {
                    diagnostic(machine.component.file, name.line, name.pos, name.text.length, message, DiagnosticSeverity.Error)
                } else {
                    diagnostic(file, 1, 1, 1, message, DiagnosticSeverity.Error)
                }
            }
            return diagnostics to "Model check: ${result.message}"
        } catch (e : ProBError) {
            return convertErrorItems(e.errors, file, communicator) to "Model check failed"
        } finally {
            stateSpace.kill()
        }
    }

    /**
     * Does the main work
     * @param settings the settings of the document
     * @param file the path to the document
     * @param load loads the model into the given state space
     */
    private fun loadMachine(settings: ProBSettings, file : File, loaded : (StateSpace) -> Unit = {}, load : (StateSpace) -> Unit): List<Diagnostic> {
        communicator.sendDebugMessage("creating new state space", MessageType.Info)

        val newStateSpace = animator.createStateSpace()
        communicator.sendDebugMessage("setting preferences", MessageType.Info)
        newStateSpace.changePreferences(prepareAdditionalSettings(settings))

        val errors = mutableListOf<ErrorItem>()

        try {
            load(newStateSpace)
        } catch (e: IOException) {
            communicator.sendDebugMessage("IOException ${e.message}", MessageType.Info)
        } catch (e : ProBError){
            errors.addAll(e.errors)
        }

        if(errors.none { errorItem -> errorItem.type == ErrorItem.Type.ERROR }) {
            communicator.sendDebugMessage("No fatal errors found, continuing with option steps...", MessageType.Info)
            try {
                loaded(newStateSpace)
            } catch (e : ProBError) {
                communicator.sendDebugMessage("could not describe identifiers: ${e.message}", MessageType.Info)
            }

            if (settings.strictChecks) {
                errors.addAll(newStateSpace.performExtendedStaticChecks())
            }

            try {
                communicator.sendDebugMessage("executing optional steps", MessageType.Info)
                executeAdditionalOptions(settings)
            } catch (e: RuntimeException) {
                communicator.sendDebugMessage("something bad happened, statespace was killed", MessageType.Warning)
                newStateSpace.kill()
            }

        }

        communicator.sendDebugMessage("processing errors", MessageType.Info)
        newStateSpace.kill()
        return convertErrorItems(errors, file, communicator)
    }


    /**
     * executes additional commands, which are defined as separate command, all others are executed at
     * @see prepareAdditionalSettings
     * @param settings the settings to be executed as command
     */
    private fun executeAdditionalOptions(settings : ProBSettings){
        if(settings.wdChecks){
            communicator.sendDebugMessage("doing WD checks", MessageType.Info)
           animator.execute(CheckWellDefinednessCommand())
        }
    }

    /**
     * to clean up a machine
     */
    private fun unloadMachine() {

        val oldTrace = animationSelector.currentTrace
        if (oldTrace != null) {
            assert(oldTrace.stateSpace === animator.currentStateSpace)
            animationSelector.changeCurrentAnimation(null)
            oldTrace.stateSpace.kill()
        }

    }


    /**
     * prepares a map with additional settings
     * note that the -lint option is an extra call to the state space and must be performed elsewhere
     * @param proBSettings the settings to put in the map
     * @return the settings map
     */
    private fun prepareAdditionalSettings(proBSettings: ProBSettings): MutableMap<String, String> {
        val settings = mutableMapOf<String, String>()
        if(proBSettings.performanceHints) {
            settings["STRICT_CLASH_CHECKING"] = "TRUE"
            settings["TYPE_CHECK_DEFINITIONS"] = "TRUE"
        }else{
            settings["STRICT_CLASH_CHECKING"] = "TRUE"
            settings["TYPE_CHECK_DEFINITIONS"] = "TRUE"
        }
        if (proBSettings.strictChecks) {
            settings["PERFORMANCE_INFO"] = "TRUE"
        }else{
            settings["PERFORMANCE_INFO"] = "TRUE"
        }
        return settings
    }


}