package b.language.server.config

import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SettingsTest {
    private fun parse(json: String) = Settings.from(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `missing settings fall back to the defaults`() {
        assertEquals(Settings(), parse("{}"))
        assertEquals(Settings(), Settings.from(null))
    }

    @Test
    fun `given settings are read`() {
        val settings =
            parse(
                """{"strictChecks": false, "wdChecks": false, "performanceHints": false, "probHome": "/opt/prob",
                   "debugMode": false, "poTimeout": 5, "modelCheckStates": 7}""",
            )
        assertEquals(Settings(false, false, false, "/opt/prob", false, 5, 7), settings)
    }

    @Test
    fun `proBHome is read as probHome`() {
        assertEquals("/opt/prob", parse("""{"proBHome": "/opt/prob"}""").probHome)
    }

    @Test
    fun `null and unknown keys are ignored`() {
        assertEquals(Settings(poTimeout = 5), parse("""{"probHome": null, "poTimeout": 5, "maxNumberOfProblems": 100}"""))
    }

    @Test
    fun `numbers and booleans given as strings are read`() {
        assertEquals(Settings(poTimeout = 5, debugMode = false), parse("""{"poTimeout": "5", "debugMode": "false"}"""))
    }

    @Test
    fun `serialized settings are read back unchanged`() {
        val settings = Settings(false, false, false, "/opt/prob", false, 5, 7)
        assertEquals(settings, parse(Json.encodeToString(settings)))
    }

    @Test
    fun `settings are serialized with the keys the client uses`() {
        assertEquals(
            """{"strictChecks":true,"wdChecks":true,"performanceHints":true,"probHome":"DEFAULT",""" +
                """"debugMode":true,"poTimeout":1000,"modelCheckStates":100000}""",
            Json { encodeDefaults = true }.encodeToString(Settings()),
        )
    }
}
