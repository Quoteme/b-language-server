package b.language.server.analysis.prob

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Stage
import de.prob.MainModule

/** The Guice module of prob-java, which starts a ProB process per injector. */
class ProBModule : AbstractModule() {
    override fun configure() {
        install(MainModule())
    }

    companion object {
        fun injector(): Injector = Guice.createInjector(Stage.PRODUCTION, ProBModule())
    }
}
