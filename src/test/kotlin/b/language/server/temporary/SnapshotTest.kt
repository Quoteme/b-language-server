package b.language.server.temporary

import com.google.gson.JsonParser
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Snapshots of everything the server tells a client about the fixtures: problems, hovers,
 * completions, proof obligations, model checking and animation. They pin the behaviour while
 * the analysis is restructured.
 */
class SnapshotTest {
    private val resources = File("src/test/resources").absoluteFile
    private val eventB =
        listOf("Bank_ctx", "Bank", "Bank_r1", "Counter_ctx", "Counter", "Counter_r1", "StructureError", "SyntaxError", "TypeErrors")
            .map { File(resources, "eventb/$it.mch") }
    private val classicB = listOf("classicB/Doors", "WD_M1", "WD_M2", "Lift").map { File(resources, "$it.mch") }
    private val all = eventB + classicB

    private fun name(file: File) = file.relativeTo(resources).path.removeSuffix(".mch")

    /** A test per file, each with its own harness, which is closed afterwards to end its ProB processes. */
    private fun forEach(
        files: List<File>,
        test: (Harness, File) -> Unit,
    ) = files.map { file -> DynamicTest.dynamicTest(name(file)) { Harness().use { test(it, file) } } }

    /** Where hovers are asked for: every word start and every other visible character. */
    private fun positions(file: File): List<Position> =
        file.readLines().flatMapIndexed { line, text ->
            text.indices
                .filter { column ->
                    val char = text[column]
                    !char.isWhitespace() && (column == 0 || !isWord(char) || !isWord(text[column - 1]))
                }.map { Position(line, it) }
        }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_' || c == '\'' || c == '@'

    private data class Position(
        val line: Int,
        val column: Int,
    )

    @TestFactory
    fun diagnostics() =
        forEach(all) { harness, file ->
            harness.open(file)
            Snapshot.check("diagnostics/${name(file)}", Snapshot.normalize(Harness.render(harness.communicator.diagnostics)))
        }

    @TestFactory
    fun hover() =
        forEach(all) { harness, file ->
            harness.open(file)
            val texts = LinkedHashMap<String, String>()
            val lines =
                positions(file).mapNotNull { (line, column) ->
                    harness.hover(file, line, column)?.let { text ->
                        val hash = Snapshot.hash(text)
                        texts[hash] = text
                        "$line:$column #$hash"
                    }
                }
            val bodies = texts.entries.joinToString("") { (hash, text) -> "\n=== #$hash\n$text\n" }
            Snapshot.check("hover/${name(file)}", Snapshot.normalize(lines.joinToString("\n") + "\n" + bodies))
        }

    @TestFactory
    fun completion() =
        forEach(all) { harness, file ->
            harness.open(file)
            val texts = LinkedHashMap<String, String>()
            val asked = mutableSetOf<String>()
            val out = StringBuilder()
            file.readLines().forEachIndexed { line, text ->
                Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(text).forEach { word ->
                    for (length in 1..minOf(2, word.value.length)) {
                        val prefix = word.value.take(length)
                        if (!asked.add("$prefix@${if (length == 1) "" else line}")) continue
                        out.append("$line:${word.range.first + length} '$prefix'\n")
                        harness.completion(file, line, word.range.first + length).forEach { item ->
                            val documentation = item.documentation?.right?.value
                            val hash = documentation?.let { Snapshot.hash(it).also { h -> texts[h] = it } }
                            out.append("  ${item.label} ${item.kind} ${item.detail} ${hash?.let { "#$it" } ?: "-"}\n")
                        }
                    }
                }
            }
            texts.forEach { (hash, text) -> out.append("\n=== #$hash\n$text\n") }
            Snapshot.check("completion/${name(file)}", Snapshot.normalize(out.toString()))
        }

    @TestFactory
    fun proofObligations() =
        forEach(eventB) { harness, file ->
            harness.open(file)
            Snapshot.check("pos/${name(file)}", Snapshot.normalize(harness.showPOs(file)))
        }

    @TestFactory
    fun modelCheck() =
        forEach(listOf("eventb/Bank", "eventb/Counter", "eventb/Counter_r1", "eventb/TypeErrors", "classicB/Doors", "WD_M1").map {
            File(resources, "$it.mch")
        }) { harness, file ->
            harness.open(file)
            harness.communicator.clear()
            harness.modelCheck(file)
            // ProB explores the states in random order, so the path to an error varies between runs.
            val report = Harness.render(harness.communicator.diagnostics) + "\n" + harness.communicator.messages.joinToString("\n")
            Snapshot.check("modelcheck/${name(file)}", Snapshot.normalize(report).replace(Regex(""" after [^\n]*"""), " after <path>"))
        }

    @TestFactory
    fun animation() =
        forEach(listOf("eventb/Bank", "eventb/Counter_ctx", "eventb/Counter", "eventb/TypeErrors", "classicB/Doors").map {
            File(resources, "$it.mch")
        }) { harness, file ->
            val steps = mutableListOf<String>()

            fun step(vararg fields: Pair<String, Any>) = harness.animate(*fields).also { steps += it }

            fun firstEnabled(view: String) =
                JsonParser
                    .parseString(view)
                    .asJsonObject
                    .getAsJsonArray("enabled")
                    ?.firstOrNull()
                    ?.asJsonObject
                    ?.get("id")
                    ?.asString

            var view = step("action" to "start", "uri" to file.toURI().toString())
            repeat(3) {
                val id = firstEnabled(view) ?: return@repeat
                view = step("action" to "execute", "argument" to id)
            }
            step("action" to "back", "count" to 2)
            step("action" to "forward", "count" to 1)
            step("action" to "goto", "argument" to "1")
            step("action" to "find", "argument" to "1 = 2")
            step("action" to "view")
            step("action" to "stop")
            step("action" to "view")
            Snapshot.check("animation/${name(file)}", Snapshot.normalize(steps.joinToString("\n")))
        }
}
