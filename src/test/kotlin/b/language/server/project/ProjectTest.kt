package b.language.server.project

import b.language.server.Fixtures
import b.language.server.project.classicB.ClassicBProject
import b.language.server.project.eventB.EventBProject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProjectTest {
    private val file = File("/models/m.mch")

    @Test
    fun `lowercase machine and context are Event-B in Camille notation`() {
        assertIs<EventBProject>(Project.of(file, "machine m0\nend"))
        assertIs<EventBProject>(Project.of(file, "// a comment\n/* another */\ncontext c0\nend"))
    }

    @Test
    fun `everything else is classical B`() {
        assertIs<ClassicBProject>(Project.of(file, "MACHINE M\nEND"))
        assertIs<ClassicBProject>(Project.of(file, ""))
    }

    @Test
    fun `match calls the function of the project's kind`() {
        val kind = Project.of(file, "context c0\nend").match({ "classical B" }, { "Event-B" })
        assertEquals("Event-B", kind)
    }

    @Test
    fun `files the model names are found next to the main file`() {
        val project = Project.of(file, "MACHINE M\nEND")
        assertEquals(File("/models/images/door.gif"), project.fileNamed("images/door.gif"))
        assertEquals(File("/elsewhere/door.gif"), project.fileNamed("/elsewhere/door.gif"))
    }

    @Test
    fun `open documents are preferred to the files on disk`() {
        val documents = OpenDocuments()
        val fixture = Fixtures.file("classicB/Doors.mch")
        assertEquals(fixture.readText(), documents.textOf(fixture))
        documents.update(fixture, "MACHINE Edited\nEND")
        assertEquals("MACHINE Edited\nEND", documents.textOf(fixture))
        assertEquals(null, documents.textOf(File("/does/not/exist.mch")))
    }
}
