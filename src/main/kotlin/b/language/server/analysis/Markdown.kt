package b.language.server.analysis

/** Documentation in Markdown, as hovers and completions show it. */
@JvmInline
value class Markdown(
    val text: String,
) {
    /** The first line of a leading code block: the signature of what is documented. */
    val signature: String? get() = text.takeIf { it.startsWith("```") }?.lines()?.getOrNull(1)
}
