package b.language.server.analysis.documentation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One documented keyword, built-in or operator, generated from the official documentation by
 * scripts/generate_documentation.py. [primary] tells whether [key] names the entry rather than
 * being part of it, like THEN in IF … THEN … END.
 */
@Serializable
data class OperatorEntry(
    val key: String,
    val title: String,
    val description: String,
    val ascii: String? = null,
    val definition: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val wd: List<String> = emptyList(),
    val link: String,
    val source: String,
    val primary: Boolean,
    val symbol: String,
) {
    val sourceLink get() = Link(link, source)
}

/** A hand-written example of a documented construct, by the [title] of its entry. */
@Serializable
data class Example(
    val language: CodeLanguage,
    val title: String,
    val kind: Kind,
    val text: String,
) {
    @Serializable
    enum class Kind {
        /** The usage shown first, as the signature. */
        @SerialName("sig")
        SIGNATURE,

        /** A predicate that must be true. */
        @SerialName("pred")
        PREDICATE,

        /** A code snippet. */
        @SerialName("code")
        CODE,
    }
}
