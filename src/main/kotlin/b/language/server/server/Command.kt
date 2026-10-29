package b.language.server.server

/** The commands of the server; those with a title are offered as source actions of a file. */
enum class Command(
    val id: String,
    val title: String? = null,
) {
    MODEL_CHECK("prob.modelCheck", "ProB: model check for deadlocks and invariant violations"),
    DISCHARGE_POS("prob.dischargePOs", "ProB: discharge proof obligations with a longer timeout"),
    SHOW_POS("prob.showPOs", "ProB: list proof obligations"),

    /** Handled by the client, which opens its animation panel and then sends [ANIMATE]. */
    OPEN_ANIMATION("prob.animate.open", "ProB: animate"),
    ANIMATE("prob.animate"),

    /** Shows a visualisation of the animation's current state, as ProB Tcl/Tk does. */
    VISUALIZE("prob.visualize"),
    ;

    companion object {
        /** The commands the server executes itself. */
        val handledByServer: List<Command> get() = entries.filter { it != OPEN_ANIMATION }

        fun of(id: String): Command? = entries.firstOrNull { it.id == id }
    }
}
