package b.language.server.analysis.legacy

import b.language.server.client.CommunicatorInterface
import de.prob.animator.IWarningListener
import de.prob.animator.domainobjects.ErrorItem
import org.eclipse.lsp4j.Diagnostic
import java.io.File

/**
 * Custom collector to collect warnings from prob kernel
 */
class InformationListener(
    private val fallbackPath: File,
    private val communicator: CommunicatorInterface,
) : IWarningListener {
    private val warningList: ArrayList<ErrorItem> = arrayListOf()

    override fun warningsOccurred(warnings: MutableList<ErrorItem>?) {
        warningList.addAll(warnings!!.toList())
    }

    fun getInformation(): List<Diagnostic> = convertErrorItems(warningList, fallbackPath, communicator)
}
