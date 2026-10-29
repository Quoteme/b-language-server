package b.language.server.analysis.prob.animation

import b.language.server.Fixtures
import b.language.server.analysis.classicB.ClassicBAnimationModel
import b.language.server.analysis.eventB.EventBAnimationModel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnimationTest {
    @Test
    fun `nothing can be done before an animation is started`() {
        Animation().use { animation ->
            assertEquals(Failure("No animation is running"), animation.perform(AnimationAction.VIEW, null, 1))
            assertEquals(Failure("No animation is running"), animation.visualize(VisualizationRequest()))
        }
    }

    @Test
    fun `a machine is animated step by step`() {
        Animation().use { animation ->
            val start = assertIs<AnimationView>(animation.start(ClassicBAnimationModel(Fixtures.classicB("classicB/Doors.mch"))))
            assertEquals("Doors", start.machine)
            assertEquals(listOf("SETUP_CONSTANTS"), start.enabled.map { it.name })
            assertEquals(5, start.constants.single().line)

            val next = assertIs<AnimationView>(animation.perform(AnimationAction.EXECUTE, start.enabled.single().id, 1))
            assertEquals(1, next.step)
            assertTrue(next.constantsSetUp)
            assertEquals(Stopped(), animation.stop())
        }
    }

    @Test
    fun `a model with errors is not animated`() {
        Animation().use { animation ->
            val failure = assertIs<Failure>(animation.start(EventBAnimationModel(Fixtures.eventB("TypeErrors"))))
            assertTrue(failure.error.startsWith("The model has errors; fix them first:"), failure.error)
        }
    }
}
