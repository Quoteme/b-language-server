package b.language.server.analysis.documentation

import kotlinx.serialization.json.Json

/** The documented keys of one language, by key, in the order of the official documentation. */
class OperatorTable(
    entries: List<OperatorEntry>,
) {
    private val byKey: Map<String, List<OperatorEntry>> = entries.groupBy { it.key }

    operator fun get(key: String): List<OperatorEntry>? = byKey[key]

    /** Every key with its entries. */
    val keys: Set<String> get() = byKey.keys
}

/** Signatures and examples of a documented construct. */
data class Extras(
    val signature: String?,
    val examples: List<String>,
)

/** The documentation tables in the resources, read once. */
object DocumentationTables {
    val eventB: OperatorTable by lazy { OperatorTable(read("eventb")) }
    val classicB: OperatorTable by lazy { OperatorTable(read("classical")) }

    /** The extras by language and title. */
    private val extras: Map<CodeLanguage, Map<String, Extras>> by lazy {
        read<Example>("examples").groupBy { it.language }.mapValues { (_, examples) ->
            examples.groupBy { it.title }.mapValues { (_, own) ->
                Extras(
                    own.firstOrNull { it.kind == Example.Kind.SIGNATURE }?.text,
                    own.filter { it.kind != Example.Kind.SIGNATURE }.map { it.text },
                )
            }
        }
    }

    fun extras(
        language: CodeLanguage,
        title: String,
    ): Extras? = extras[language]?.get(title)

    private inline fun <reified T> read(name: String): List<T> {
        val text =
            DocumentationTables::class.java
                .getResourceAsStream("/documentation/$name.json")
                ?.bufferedReader(Charsets.UTF_8)
                ?.readText() ?: return emptyList()
        return Json.decodeFromString(text)
    }
}
