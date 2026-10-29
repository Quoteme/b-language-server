package b.language.server.analysis.prob

import b.language.server.analysis.Problem
import b.language.server.analysis.Severity
import b.language.server.project.Location
import b.language.server.project.SourcePosition
import b.language.server.project.SourceRange
import de.prob.animator.domainobjects.ErrorItem
import java.io.File

/** The problems ProB reported; locations without a file name belong to [checked]. */
fun List<ErrorItem>.toProblems(checked: File): List<Problem> = flatMap { it.toProblems(checked) }

fun ErrorItem.toProblems(checked: File): List<Problem> =
    locations
        .map { location ->
            val range =
                SourceRange(
                    SourcePosition(location.startLine - 1, location.startColumn),
                    SourcePosition(location.endLine - 1, location.endColumn),
                )
            Problem(Location(File(location.filename.ifEmpty { checked.absolutePath }), range), message, type.toSeverity())
        }.ifEmpty { listOf(Problem(Location.startOf(checked), message, type.toSeverity())) }

fun ErrorItem.Type.toSeverity(): Severity =
    when (this) {
        ErrorItem.Type.WARNING -> Severity.WARNING
        ErrorItem.Type.MESSAGE -> Severity.INFORMATION
        ErrorItem.Type.ERROR, ErrorItem.Type.INTERNAL_ERROR -> Severity.ERROR
    }
