package b.language.server.server

import b.language.server.Fixtures
import b.language.server.analysis.AnalysisDispatcher
import b.language.server.analysis.prob.startProB
import b.language.server.client.RecordingCommunicator
import b.language.server.project.OpenDocuments
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The JSON the client reads from the commands, as lsp4j writes it; b.nvim depends on these names. */
class WireFormatTest {
    private val analysis = AnalysisDispatcher(startProB())
    private val documents = OpenDocuments()
    private val checks = DocumentChecks(DocumentChecksTest::defaultSettings, RecordingCommunicator(), analysis, documents)
    private val commands = Commands(checks, documents, analysis)
    private val gson = MessageJsonHandler(emptyMap()).gson

    @AfterEach
    fun close() {
        commands.close()
        analysis.close()
    }

    private fun command(
        command: Command,
        argument: Any,
    ) = JsonParser.parseString(gson.toJson(commands.execute(command.id, listOf(argument)).get()))

    @Test
    fun `proof obligations are listed with their status by name`() {
        val counter = Fixtures.file("eventb/Counter.mch")
        checks.check(counter)
        val obligations = command(Command.SHOW_POS, counter.toURI().toString()).asJsonArray.map { it.asJsonObject }
        val inv2 = obligations.first { it["name"].asString == "inc/inv2/INV" }
        assertEquals("disproved", inv2["status"].asString)
        assertEquals(setOf("uri", "line", "character", "name", "status", "detail"), inv2.keySet())
        assertEquals("discharged", obligations.first { it["name"].asString == "INITIALISATION/inv1/INV" }["status"].asString)
    }

    @Test
    fun `the animation names its visualisations by kind`() {
        val start =
            JsonObject().apply {
                addProperty("action", "start")
                addProperty("uri", Fixtures.file("classicB/Doors.mch").toURI().toString())
            }
        val view = command(Command.ANIMATE, start).asJsonObject
        assertEquals("Doors", view["machine"].asString)
        assertEquals(setOf("dot", "table"), view["visualizations"].asJsonArray.map { it.asJsonObject["kind"].asString }.toSet())
        command(Command.ANIMATE, JsonObject().apply { addProperty("action", "stop") })
    }

    @Test
    fun `a request of the wrong shape is answered with an error`() {
        val request = JsonObject().apply { addProperty("action", "start") }.apply { addProperty("count", "many") }
        val answer = command(Command.ANIMATE, request).asJsonObject
        assertEquals(setOf("error"), answer.keySet())
    }
}
