package b.language.server.analysis.prob

import arrow.core.getOrElse
import kotlin.test.fail

/** Starts ProB for a test, which fails if ProB cannot be started. */
fun startProB(): ProBSession = ProBSession.start().getOrElse { fail("ProB could not be started: ${it.message}") }
