package b.language.server.analysis.prob.animation

import b.language.server.client.SerializedNames
import b.language.server.project.Location
import com.google.gson.annotations.JsonAdapter

/*
 * What the animation answers the client. lsp4j serialises these with Gson, field by field, so the
 * property names are the JSON keys the client reads; null properties are left out.
 */

/** An answer to an animation request. */
sealed interface AnimationResponse

/** An answer to a visualisation request. */
sealed interface VisualizationResponse

data class Failure(
    val error: String,
) : AnimationResponse,
    VisualizationResponse

data class Stopped(
    val stopped: Boolean = true,
) : AnimationResponse

/** A file the client shows, such as a rendered image or a page that follows the animation. */
data class Page(
    val path: String,
) : VisualizationResponse

data class Table(
    val title: String,
    val header: List<String>,
    val rows: List<List<String>>,
) : VisualizationResponse

/** The state of the animation, as the client's panel shows it. */
data class AnimationView(
    val machine: String,
    val file: String,
    val step: Int,
    val history: List<HistoryEntry>,
    val canBack: Boolean,
    val canForward: Boolean,
    val initialised: Boolean,
    val constantsSetUp: Boolean,
    val constants: List<NamedValue>,
    val variables: List<NamedValue>,
    val invariants: List<PredicateStatus>,
    val enabled: List<EnabledEvent>,
    val disabled: List<DisabledEvent>,
    val deadlock: Boolean,
    val context: Boolean,
    val errors: List<String>,
    val message: String,
    val graphics: List<List<GraphicsCell>>?,
    val visualizations: List<VisualizationEntry>,
    val visb: Boolean,
) : AnimationResponse

data class HistoryEntry(
    val label: String,
    val index: Int,
    val future: Boolean,
)

/** A constant or variable, its value, and whether the last step changed it. */
data class NamedValue(
    val name: String,
    val value: String?,
    val changed: Boolean,
    val uri: String?,
    val line: Int?,
    val character: Int?,
) {
    constructor(name: String, value: String?, changed: Boolean, at: Location?) :
        this(name, value, changed, at.uri, at?.start?.line, at?.start?.column)
}

/** An invariant or axiom, and whether it holds (null if ProB cannot tell). */
data class PredicateStatus(
    val label: String,
    val ok: Boolean?,
    val uri: String?,
    val line: Int?,
    val character: Int?,
) {
    constructor(label: String, ok: Boolean?, at: Location?) : this(label, ok, at.uri, at?.start?.line, at?.start?.column)
}

data class EnabledEvent(
    val id: String,
    val name: String,
    val event: String,
    val parameters: String,
    val uri: String?,
    val line: Int?,
    val character: Int?,
) {
    constructor(id: String, name: String, event: String, parameters: String, at: Location?) :
        this(id, name, event, parameters, at.uri, at?.start?.line, at?.start?.column)
}

data class GuardStatus(
    val label: String,
    val ok: Boolean?,
)

data class DisabledEvent(
    val name: String,
    val guards: List<GuardStatus>,
    val uri: String?,
    val line: Int?,
    val character: Int?,
) {
    constructor(name: String, guards: List<GuardStatus>, at: Location?) :
        this(name, guards, at.uri, at?.start?.line, at?.start?.column)
}

/** A cell of the graphical animation: a text, or an image with its name as text. */
data class GraphicsCell(
    val text: String,
    val image: String? = null,
)

/** A visualisation of ProB Tcl/Tk's Visualize menu. */
data class VisualizationEntry(
    @JsonAdapter(SerializedNames::class)
    val kind: VisualizationKind,
    val command: String,
    val name: String,
    val description: String,
    val arity: Int,
    val available: Boolean,
    val reason: String,
)

private val Location?.uri: String? get() =
    this
        ?.file
        ?.toPath()
        ?.toUri()
        ?.toString()
