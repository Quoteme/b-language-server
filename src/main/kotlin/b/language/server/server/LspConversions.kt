package b.language.server.server

import b.language.server.analysis.CandidateKind
import b.language.server.analysis.CompletionCandidate
import b.language.server.analysis.Problem
import b.language.server.analysis.Severity
import b.language.server.project.SourcePosition
import b.language.server.project.SourceRange
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.io.File
import java.net.URI

// How the results of the analysis are told to the client.

/** The file a client's `file://` URI names. */
fun fileOf(uri: String) = File(URI(uri).path)

fun SourcePosition.toLsp() = Position(line, column)

fun SourceRange.toLsp() = Range(start.toLsp(), end.toLsp())

fun Severity.toLsp(): DiagnosticSeverity =
    when (this) {
        Severity.ERROR -> DiagnosticSeverity.Error
        Severity.WARNING -> DiagnosticSeverity.Warning
        Severity.INFORMATION -> DiagnosticSeverity.Information
        Severity.HINT -> DiagnosticSeverity.Hint
    }

fun Problem.toDiagnostic(source: String) = Diagnostic(location.range.toLsp(), message, severity.toLsp(), source)

fun CandidateKind.toLsp(): CompletionItemKind =
    when (this) {
        CandidateKind.KEYWORD -> CompletionItemKind.Keyword
        CandidateKind.FUNCTION -> CompletionItemKind.Function
        CandidateKind.CLASS -> CompletionItemKind.Class
        CandidateKind.ENUM -> CompletionItemKind.Enum
        CandidateKind.ENUM_MEMBER -> CompletionItemKind.EnumMember
        CandidateKind.CONSTANT -> CompletionItemKind.Constant
        CandidateKind.VARIABLE -> CompletionItemKind.Variable
        CandidateKind.EVENT -> CompletionItemKind.Event
        CandidateKind.VALUE -> CompletionItemKind.Value
    }

fun CompletionCandidate.toLsp() =
    CompletionItem(word).apply {
        kind = this@toLsp.kind.toLsp()
        this@toLsp.documentation?.let { markdown ->
            detail = markdown.signature
            documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, markdown.text))
        }
    }
