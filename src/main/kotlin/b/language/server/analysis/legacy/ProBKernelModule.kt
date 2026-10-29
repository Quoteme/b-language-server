package b.language.server.analysis.legacy

import b.language.server.client.CommunicatorInterface
import com.google.inject.AbstractModule
import de.prob.MainModule

class ProBKernelModule(
    private val communicator: CommunicatorInterface,
) : AbstractModule() {
    override fun configure() {
        install(MainModule())
        bind(CommunicatorInterface::class.java).toInstance(communicator)
    }
}
