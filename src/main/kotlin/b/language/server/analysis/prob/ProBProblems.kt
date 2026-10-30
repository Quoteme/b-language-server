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

/** Why loading failed, as problems of [checked] or of the file that could not be read. */
fun LoadFailure.toProblems(checked: File): List<Problem> =
    when (this) {
        is LoadFailure.Rejected -> failure.errors.toProblems(checked)
        is LoadFailure.Unreadable -> listOf(Problem(Location.startOf(file), "Could not read ${file.name}: $reason", Severity.ERROR))
    }
