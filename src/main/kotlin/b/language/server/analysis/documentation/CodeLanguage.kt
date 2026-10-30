package b.language.server.analysis.documentation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The language of code blocks in documentation, as Markdown names it. */
@Serializable
enum class CodeLanguage(
    val fence: String,
) {
    @SerialName("b")
    CLASSIC_B("b"),

    @SerialName("eventb")
    EVENT_B("eventb"),
}
