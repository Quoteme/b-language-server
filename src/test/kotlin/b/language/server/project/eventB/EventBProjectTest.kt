package b.language.server.project.eventB

import b.language.server.Fixtures
import b.language.server.project.DeclarationKind
import b.language.server.project.SourcePosition
import b.language.server.project.eventB.EventBProject.ComponentKind
import b.language.server.project.eventB.EventBProject.Loading
import de.be4.eventb.core.parser.node.AMachineParseUnit
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventBProjectTest {
    @Test
    fun `the main component is loaded with its kind and name`() {
        val loaded = assertIs<Loading.Loaded>(Fixtures.eventB("Bank").loadMain())
        assertEquals(ComponentKind.MACHINE, loaded.component.kind)
        assertEquals("Bank", loaded.component.name.text)
    }

    @Test
    fun `referenced components are found next to the referencing file`() {
        val project = Fixtures.eventB("Bank")
        val bank = assertIs<Loading.Loaded>(project.loadMain()).component
        val context = assertIs<Loading.Loaded>(project.resolve(bank, (bank.unit as AMachineParseUnit).seenNames.first()))
        assertEquals(ComponentKind.CONTEXT, context.component.kind)
        assertEquals(Fixtures.file("eventb/Bank_ctx.mch"), context.component.file)
    }

    @Test
    fun `a missing component is reported where it is referenced`() {
        val dir = Files.createTempDirectory("eventb").toFile().apply { deleteOnExit() }
        val main = dir.resolve("m0.mch").apply { writeText("machine m0 sees missing\nend\n") }
        val project = EventBProject(main, main.readText())
        val m0 = assertIs<Loading.Loaded>(project.loadMain()).component
        val failed = assertIs<Loading.Failed>(project.resolve(m0, (m0.unit as AMachineParseUnit).seenNames.first()))
        val error = failed.errors.single()
        assertEquals(main, error.location.file)
        assertEquals("Component 'missing' not found, expected it in missing.mch", error.message)
    }

    @Test
    fun `parse errors are located, with a hint for clauses in the wrong kind of component`() {
        val failed = assertIs<Loading.Failed>(Fixtures.eventB("StructureError").loadMain())
        assertEquals(
            SourcePosition(5, 2),
            failed.errors
                .single()
                .location.start,
        )

        val dir = Files.createTempDirectory("eventb").toFile().apply { deleteOnExit() }
        val main = dir.resolve("m0.mch").apply { writeText("machine m0\nconstants c\nend\n") }
        val hinted = assertIs<Loading.Failed>(EventBProject(main, main.readText()).loadMain()).errors.single()
        assertTrue(hinted.message.startsWith("extends, sets, constants and axioms belong in a context"), hinted.message)
    }

    @Test
    fun `declarations are searched in the loaded components, the main file first`() {
        val project = Fixtures.eventB("Bank")
        val bank = assertIs<Loading.Loaded>(project.loadMain()).component
        project.resolve(bank, (bank.unit as AMachineParseUnit).seenNames.first())

        assertEquals(Fixtures.file("eventb/Bank.mch"), project.declaration("clients", DeclarationKind.VARIABLE)?.file)
        val constant = project.declaration("maxClients", DeclarationKind.CONSTANT)
        assertEquals(Fixtures.file("eventb/Bank_ctx.mch"), constant?.file)
        assertEquals(SourcePosition(5, 2), constant?.start)
        assertEquals(null, project.declaration("INVARIANT", DeclarationKind.INVARIANT))
    }
}
