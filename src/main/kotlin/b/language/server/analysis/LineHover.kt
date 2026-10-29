package b.language.server.analysis

import b.language.server.analysis.documentation.LineText
import b.language.server.project.Project
import b.language.server.project.SourcePosition

/** A hover that documents what is written at the cursor in the line it is on. */
abstract class LineHover<in P : Project> : HoverProvider<P> {
    final override fun hover(
        project: P,
        at: SourcePosition,
    ): Markdown? = LineText.of(project.text, at.line)?.let { hover(project, it, at) }

    protected abstract fun hover(
        project: P,
        line: LineText,
        at: SourcePosition,
    ): Markdown?
}
