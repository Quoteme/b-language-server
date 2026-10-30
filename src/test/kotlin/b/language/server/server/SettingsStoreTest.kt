package b.language.server.server

import b.language.server.client.RecordingCommunicator
import b.language.server.config.Settings
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

class SettingsStoreTest {
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    private val file = File("Example.mch")

    @Test
    fun `a client that can be asked is asked for every check`() {
        val asked = mutableListOf<String>()
        val store =
            SettingsStore(RecordingCommunicator()) { scope ->
                asked += scope
                CompletableFuture.completedFuture(json("""{"poTimeout": 5}"""))
            }
        store.push(json("""{"poTimeout": 7}"""))
        assertEquals(5, store.settingsOf(file).get().poTimeout)
        assertEquals(5, store.settingsOf(file).get().poTimeout)
        assertEquals(listOf(file.absolutePath, file.absolutePath), asked)
    }

    @Test
    fun `a client that cannot be asked has the settings it pushed last`() {
        val store = SettingsStore(RecordingCommunicator()) { error("the client cannot be asked") }
        store.canAsk = false
        assertEquals(Settings(), store.settingsOf(file).get())
        store.push(json("""{"poTimeout": 7}"""))
        assertEquals(7, store.settingsOf(file).get().poTimeout)
    }

    @Test
    fun `invalid settings fall back to the defaults`() {
        val store = SettingsStore(RecordingCommunicator()) { CompletableFuture.completedFuture(json("""{"poTimeout": "soon"}""")) }
        assertEquals(Settings(), store.settingsOf(file).get())
    }
}
