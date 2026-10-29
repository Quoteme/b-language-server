package b.language.server.analysis.prob

import b.language.server.analysis.Severity
import b.language.server.server.toDiagnostic
import b.language.server.server.toLsp
import de.prob.animator.domainobjects.ErrorItem
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

class ProBProblemsTest {
    @Test
    fun generateErrorItem() {
        val startLine = 100
        val statCol = 100
        val endLine = 101
        val endCol = 101
        val message = "hello"
        val file = File("/test")

        val errorItem =
            ErrorItem(
                message,
                ErrorItem.Type.INTERNAL_ERROR,
                listOf(ErrorItem.Location(file.absolutePath, startLine, statCol, endLine, endCol)),
            )

        val diagnostic =
            Diagnostic(
                Range(
                    Position(startLine - 1, statCol),
                    Position(endLine - 1, endCol),
                ),
                message,
                DiagnosticSeverity.Error,
                "ProB",
            )

        val problem = errorItem.toProblems(File("dummy")).first()

        assertEquals(file, problem.location.file)
        assertEquals(diagnostic, problem.toDiagnostic("ProB"))
    }

    @Test
    fun errorWithoutLocation_isShownAtStartOfCheckedFile() {
        val checked = File("/checked.mch")

        val problem = ErrorItem("hello", ErrorItem.Type.ERROR, emptyList()).toProblems(checked).single()

        assertEquals(checked, problem.location.file)
        assertEquals(Range(Position(0, 0), Position(0, 0)), problem.toDiagnostic("ProB").range)
    }

    @Test
    fun errorCodeTranslation_Error1() {
        val result = ErrorItem.Type.INTERNAL_ERROR.toSeverity().toLsp()

        assertEquals(DiagnosticSeverity.Error, result)
    }

    @Test
    fun errorCodeTranslation_Error2() {
        val result = ErrorItem.Type.ERROR.toSeverity().toLsp()

        assertEquals(DiagnosticSeverity.Error, result)
    }

    @Test
    fun errorCodeTranslation_Warning() {
        val result = ErrorItem.Type.WARNING.toSeverity()

        assertEquals(Severity.WARNING, result)
    }
}
