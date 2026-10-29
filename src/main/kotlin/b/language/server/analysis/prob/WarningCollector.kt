package b.language.server.analysis.prob

import de.prob.animator.IWarningListener
import de.prob.animator.domainobjects.ErrorItem

/** Collects the warnings ProB reports while it loads and checks a model. */
class WarningCollector : IWarningListener {
    private val warnings = mutableListOf<ErrorItem>()

    val collected: List<ErrorItem> get() = warnings

    override fun warningsOccurred(warnings: List<ErrorItem>) {
        this.warnings += warnings
    }
}
