package b.language.server.analysis.prob

import b.language.server.config.Settings
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProBSessionTest {
    @Test
    fun `the user's settings decide ProB's preferences`() {
        assertEquals(
            mapOf("STRICT_CLASH_CHECKING" to "TRUE", "TYPE_CHECK_DEFINITIONS" to "TRUE", "PERFORMANCE_INFO" to "TRUE"),
            ProBPreferences.checking(Settings()).toProB(),
        )
        assertEquals(
            mapOf("STRICT_CLASH_CHECKING" to "FALSE", "TYPE_CHECK_DEFINITIONS" to "FALSE", "PERFORMANCE_INFO" to "FALSE"),
            ProBPreferences.checking(Settings(strictChecks = false, performanceHints = false)).toProB(),
        )
    }

    @Test
    fun `the home setting names prob-java's ProB or a directory`() {
        assertEquals(ProBHome.Default, ProBHome.of("DEFAULT"))
        assertEquals(ProBHome.Directory(File("/opt/prob")), ProBHome.of("/opt/prob"))
    }

    @Test
    fun `switching to a missing ProB home fails and keeps the running ProB`() {
        startProB().use { session ->
            val failure = session.use(ProBHome.of("/does/not/exist")).leftOrNull()
            assertIs<ProBUnavailable.HomeNotFound>(failure)
            assertTrue(session.withStateSpace(ProBPreferences()) { !it.isKilled })
            assertTrue(session.use(ProBHome.Default).isRight())
        }
    }
}
