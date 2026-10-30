package b.language.server.analysis.prob.animation

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The steps of an animation that the client asks for. */
@Serializable
enum class AnimationAction {
    @SerialName("start")
    START,

    @SerialName("stop")
    STOP,

    @SerialName("view")
    VIEW,

    /** Executes the transition with the id given as argument. */
    @SerialName("execute")
    EXECUTE,

    /** Executes the event named in the argument, with a predicate after a NUL character. */
    @SerialName("executeWith")
    EXECUTE_WITH,

    @SerialName("back")
    BACK,

    @SerialName("forward")
    FORWARD,

    /** Goes to the step given as argument. */
    @SerialName("goto")
    GOTO,

    @SerialName("random")
    RANDOM,

    /** Finds a state where the predicate given as argument holds. */
    @SerialName("find")
    FIND,

    @SerialName("modelCheck")
    MODEL_CHECK,

    @SerialName("restart")
    RESTART,
}

/** The visualisations of ProB Tcl/Tk, named as the client reads (kotlinx) and writes (Gson) them. */
@Serializable
enum class VisualizationKind {
    /** The graphical animation of ANIMATION_FUNCTION, as a page that follows the animation. */
    @SerialName("graphics")
    @SerializedName("graphics")
    GRAPHICS,

    /** The VisB visualisation of VISB_JSON_FILE, as a page that follows the animation. */
    @SerialName("visb")
    @SerializedName("visb")
    VISB,

    /** A dot visualisation, rendered as an image. */
    @SerialName("dot")
    @SerializedName("dot")
    DOT,

    /** A table visualisation. */
    @SerialName("table")
    @SerializedName("table")
    TABLE,
}

/** A request of the client's animation panel; unknown or missing fields take their defaults. */
@Serializable
data class AnimationRequest(
    val action: AnimationAction = AnimationAction.VIEW,
    val uri: String? = null,
    val argument: String? = null,
    val count: Int = 1,
)

@Serializable
data class VisualizationRequest(
    val kind: VisualizationKind = VisualizationKind.DOT,
    val command: String = "",
    val formulas: List<String> = emptyList(),
    val format: String = "svg",
)
