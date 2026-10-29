package b.language.server.analysis.eventB

import de.prob.animator.domainobjects.EventB
import de.prob.model.eventb.Context
import de.prob.model.eventb.Event
import de.prob.model.eventb.EventBAction
import de.prob.model.eventb.EventBAxiom
import de.prob.model.eventb.EventBConstant
import de.prob.model.eventb.EventBGuard
import de.prob.model.eventb.EventBInvariant
import de.prob.model.eventb.EventBMachine
import de.prob.model.eventb.EventBModel
import de.prob.model.eventb.EventBVariable
import de.prob.model.eventb.EventParameter
import de.prob.model.eventb.Variant
import de.prob.model.eventb.Witness
import de.prob.model.representation.AbstractElement
import de.prob.model.representation.DependencyGraph.ERefType
import de.prob.model.representation.ModelElementList
import de.prob.statespace.StateSpace
import org.eventb.core.ast.extension.IFormulaExtension
import de.prob.model.representation.Set as ProBSet

/** A checked model as prob-java represents it, ready to be loaded into ProB. */
class ProBTranslation(
    private val model: EventBModel,
    private val main: AbstractElement,
) {
    fun loadInto(stateSpace: StateSpace) = model.loadIntoStateSpace(stateSpace, main)

    companion object {
        /** Formulas use no mathematical extensions. */
        val NO_EXTENSIONS: Set<IFormulaExtension> = emptySet()

        /** Builds the prob-java model of [main] and the components it depends on, starting from [empty]. */
        fun of(
            main: CheckedComponent,
            empty: EventBModel,
        ): ProBTranslation {
            val contextModels = HashMap<String, Context>()
            val machineModels = HashMap<String, EventBMachine>()
            var model = empty

            fun context(info: CheckedContext): Context {
                contextModels[info.name]?.let { return it }
                val extended = info.extended.map { context(it) }
                val built =
                    Context(info.name)
                        .withExtends(ModelElementList(extended))
                        .withSets(ModelElementList(info.sets.map { ProBSet(EventB(it, NO_EXTENSIONS)) }))
                        .withConstants(ModelElementList(info.constants.map { EventBConstant(it, false, null) }))
                        .withAxioms(
                            ModelElementList(info.axioms.map { EventBAxiom(it.label, it.formula.toString(), it.theorem, NO_EXTENSIONS) }),
                        )
                contextModels[info.name] = built
                model = model.addContext(built)
                extended.forEach { model = model.addRelationship(info.name, it.name, ERefType.EXTENDS) }
                return built
            }

            fun machine(info: CheckedMachine): EventBMachine {
                machineModels[info.name]?.let { return it }
                val abstract = info.abstract?.let { machine(it) }
                // A machine sees the contexts of its abstractions too; ProB wants the ones named directly.
                val sees =
                    info.contexts
                        .filter { c -> info.contexts.none { other -> other !== c && c in other.closure() } }
                        .map { context(it) }

                // Extended events are flattened, as Rodin's static checker does.
                val events =
                    info.events.values.map { event ->
                        val type =
                            when (event.convergence) {
                                Convergence.CONVERGENT -> Event.EventType.CONVERGENT
                                Convergence.ANTICIPATED -> Event.EventType.ANTICIPATED
                                Convergence.ORDINARY -> Event.EventType.ORDINARY
                            }
                        val inheritance = if (event.refines.isEmpty()) Event.Inheritance.NONE else Event.Inheritance.REFINES
                        Event(event.name, type, inheritance)
                            .withParentEvent(event.refines.firstOrNull()?.let { abstract?.getEvent(it.name) })
                            .withParameters(ModelElementList(event.parameters.keys.map { EventParameter(it) }))
                            .withGuards(
                                ModelElementList(event.guards.map { EventBGuard(it.label, it.formula.toString(), it.theorem, NO_EXTENSIONS) }),
                            ).withWitnesses(ModelElementList(event.witnesses.map { Witness(it.label, it.formula.toString(), NO_EXTENSIONS) }))
                            .withActions(ModelElementList(event.actions.map { EventBAction(it.label, it.formula.toString(), NO_EXTENSIONS) }))
                    }

                var built =
                    EventBMachine(info.name)
                        .withRefinesMachine(abstract)
                        .withSees(ModelElementList(sees))
                        .withVariables(ModelElementList(info.variables.map { EventBVariable(it, null) }))
                        .withInvariants(
                            ModelElementList(
                                info.invariants.map { EventBInvariant(it.label, it.formula.toString(), it.theorem, NO_EXTENSIONS) },
                            ),
                        ).withEvents(ModelElementList(events))
                info.variant?.let { built = built.withVariant(Variant(it.formula.toString(), NO_EXTENSIONS)) }

                machineModels[info.name] = built
                model = model.addMachine(built)
                abstract?.let { model = model.addRelationship(info.name, it.name, ERefType.REFINES) }
                sees.forEach { model = model.addRelationship(info.name, it.name, ERefType.SEES) }
                return built
            }

            val element: AbstractElement =
                when (main) {
                    is CheckedMachine -> machine(main)
                    is CheckedContext -> context(main)
                }
            return ProBTranslation(model, element)
        }
    }
}
