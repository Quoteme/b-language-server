package b.language.server.project.classicB

import b.language.server.Fixtures
import b.language.server.project.DeclarationKind
import b.language.server.project.SourcePosition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ClassicBProjectTest {
    private val doors = Fixtures.classicB("classicB/Doors.mch")

    @Test
    fun `declarations are found in their section`() {
        assertEquals(SourcePosition(5, 2), doors.declaration("capacity", DeclarationKind.CONSTANT)?.start)
        assertEquals(SourcePosition(11, 8), doors.declaration("inside", DeclarationKind.VARIABLE)?.start)
    }

    @Test
    fun `an unknown name has no declaration`() {
        assertEquals(null, doors.declaration("nowhere", DeclarationKind.VARIABLE))
    }

    @Test
    fun `operations are found by their definition`() {
        assertEquals(SourcePosition(17, 0), doors.operation("open_door")?.start)
        assertEquals(SourcePosition(20, 0), doors.operation("count")?.start)
    }
}
