package b.language.server.temporary

import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals

/**
 * Snapshot files in src/test/resources/temporary. Run with UPDATE_SNAPSHOTS=1 to (re)write them;
 * otherwise the output must match the stored snapshot exactly.
 */
object Snapshot {
    private val directory = File("src/test/resources/temporary")
    private val update = System.getenv("UPDATE_SNAPSHOTS") == "1"

    fun check(
        name: String,
        actual: String,
    ) {
        val file = File(directory, "$name.txt")
        if (update || !file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        assertEquals(file.readText(), actual, "snapshot $name differs")
    }

    /** Replaces machine-specific paths, so snapshots are stable across checkouts. */
    fun normalize(text: String): String {
        val resources = File("src/test/resources").absoluteFile.toURI().path.removeSuffix("/")
        return text
            .replace("file://$resources", "file://<res>")
            .replace(resources, "<res>")
            .replace(Regex("""/tmp/prob-visualization\d+"""), "<tmp>")
            .replace(Regex("""/tmp/prob-java\d+"""), "<prob>")
    }

    fun hash(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
}
