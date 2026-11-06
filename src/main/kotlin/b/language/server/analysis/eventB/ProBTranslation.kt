package b.language.server.analysis.eventB

import arrow.core.Either
import b.language.server.analysis.prob.LoadFailure
import b.language.server.analysis.prob.ProBFailure
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

/**
 * A checked model as prob-java represents it, ready to be loaded into ProB.
 *
 * No classical B file is written: each machine of the refinement chain and each context stays a unit of its own.
 *
 * ```
 * CheckedComponent → EventBModel → AST → load_event_b_project(machines, contexts, info, _Error)
 * ```
 *
 * Rodin's ProB plugin sends a term of the same form
 * ([here](https://github.com/hhu-stups/prob2_kernel/blob/0b6f49275150943f152558ef42c4f2c13080be10/prob-java/src/main/java/de/prob/model/eventb/translate/EventBModelTranslator.java#L126), [Rodin](https://github.com/hhu-stups/prob-rodinplugin/blob/379c856a05b2f6799d2f6476cf80ecd269f1f71e/de.prob.core/src/de/prob/eventb/translator/internal/EventBTranslator.java#L150)),
 * built from the static checker's output. A formula φ reaches the AST as:
 *
 * ```
 * Here:  translate(parse(toString(φ)))
 * Rodin: translate(φ)
 * ```
 *
 * `toString` drops types, so `∅`, `id`, `prj1` and `prj2` reach ProB without the `typeof` that Rodin's route gives them.
 * Sources:
 * - [`toString`](https://github.com/systerel/RodinCore/blob/f103a3e1c14c6a3d3a173c01bd67ea3d44ac168e/org.eventb.core.ast/src/org/eventb/core/ast/Formula.java#L1237) has no types
 * - here: [`EventB` parses the string](https://github.com/hhu-stups/prob2_kernel/blob/0b6f49275150943f152558ef42c4f2c13080be10/prob-java/src/main/java/de/prob/animator/domainobjects/EventB.java#L97),
 *   and [`typeof` needs a type](https://github.com/hhu-stups/prob2_kernel/blob/0b6f49275150943f152558ef42c4f2c13080be10/prob-java/src/main/java/de/prob/formula/TranslationVisitor.java#L109)
 * - Rodin: [typed formula](https://github.com/hhu-stups/prob-rodinplugin/blob/379c856a05b2f6799d2f6476cf80ecd269f1f71e/de.prob.core/src/de/prob/eventb/translator/AbstractComponentTranslator.java#L225),
 *   [`typeof` added](https://github.com/hhu-stups/prob-rodinplugin/blob/379c856a05b2f6799d2f6476cf80ecd269f1f71e/de.prob.core/src/de/prob/eventb/translator/internal/TranslationVisitor.java#L128)
 *
 * Other differences from the plugin:
 * - Merged events: if e refines a₁, …, aₙ, only a₁ is sent, because prob-java holds one parent event. Rodin sends all.
 *   ([here](https://github.com/hhu-stups/prob2_kernel/blob/0b6f49275150943f152558ef42c4f2c13080be10/prob-java/src/main/java/de/prob/model/eventb/translate/EventBMachineTranslator.java#L147), [Rodin](https://github.com/hhu-stups/prob-rodinplugin/blob/379c856a05b2f6799d2f6476cf80ecd269f1f71e/de.prob.core/src/de/prob/eventb/translator/internal/ModelTranslator.java#L408))
 * - Missing witnesses: nothing is sent. Rodin sends the default witness ⊤.
 *   ([here: warning only][StaticChecker], [Rodin: ⊤ created](https://github.com/systerel/RodinCore/blob/f103a3e1c14c6a3d3a173c01bd67ea3d44ac168e/org.eventb.core/src/org/eventb/internal/core/sc/modules/MachineEventWitnessModule.java#L138),
 *   [Rodin: sent](https://github.com/hhu-stups/prob-rodinplugin/blob/379c856a05b2f6799d2f6476cf80ecd269f1f71e/de.prob.core/src/de/prob/eventb/translator/internal/ModelTranslator.java#L499))
 * - Proof status: none is sent. Rodin sends it.
 *   ([Rodin: collected](https://github.com/hhu-stups/prob-rodinplugin/blob/379c856a05b2f6799d2f6476cf80ecd269f1f71e/de.prob.core/src/de/prob/eventb/translator/internal/ModelTranslator.java#L183), [Rodin: sent](https://github.com/hhu-stups/prob-rodinplugin/blob/379c856a05b2f6799d2f6476cf80ecd269f1f71e/de.prob.core/src/de/prob/eventb/translator/internal/EventBTranslator.java#L158))
 * - Sees: only the contexts no other seen context extends are named. Rodin names all of them, transitively.
 *   ([Rodin: names](https://github.com/hhu-stups/prob-rodinplugin/blob/379c856a05b2f6799d2f6476cf80ecd269f1f71e/de.prob.core/src/de/prob/eventb/translator/internal/ModelTranslator.java#L317), [Rodin: transitive](https://github.com/systerel/RodinCore/blob/f103a3e1c14c6a3d3a173c01bd67ea3d44ac168e/org.eventb.core/src/org/eventb/core/ISCMachineRoot.java#L106))
 * - Invariants: prob-java repeats those of the abstract machines in each machine. Rodin sends a machine's own.
 *   ([here](https://github.com/hhu-stups/prob2_kernel/blob/0b6f49275150943f152558ef42c4f2c13080be10/prob-java/src/main/java/de/prob/model/eventb/EventBMachine.java#L114), [Rodin](https://github.com/hhu-stups/prob-rodinplugin/blob/379c856a05b2f6799d2f6476cf80ecd269f1f71e/de.prob.core/src/de/prob/eventb/translator/internal/ModelTranslator.java#L580))
 */
class ProBTranslation(
    private val model: EventBModel,
    private val main: AbstractElement,
) {
    fun loadInto(stateSpace: StateSpace): Either<LoadFailure, Unit> =
        ProBFailure.catching { model.loadIntoStateSpace(stateSpace, main) }.mapLeft(LoadFailure::Rejected)

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
                                ModelElementList(
                                    event.guards.map { EventBGuard(it.label, it.formula.toString(), it.theorem, NO_EXTENSIONS) },
                                ),
                            ).withWitnesses(
                                ModelElementList(event.witnesses.map { Witness(it.label, it.formula.toString(), NO_EXTENSIONS) }),
                            ).withActions(
                                ModelElementList(event.actions.map { EventBAction(it.label, it.formula.toString(), NO_EXTENSIONS) }),
                            )
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
