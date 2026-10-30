package b.language.server.analysis

import b.language.server.project.Location

enum class Severity { ERROR, WARNING, INFORMATION, HINT }

/** Something the analysis found at a [location]: an error, a warning, a hint, … */
data class Problem(
    val location: Location,
    val message: String,
    val severity: Severity,
)
