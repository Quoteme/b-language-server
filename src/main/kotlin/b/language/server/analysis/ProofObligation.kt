package b.language.server.analysis

import b.language.server.project.Location

/** What the provers made of a proof obligation. */
sealed interface Verdict {
    data object Discharged : Verdict

    /** The obligation does not hold, as the [counterexample] shows. */
    data class Disproved(
        val counterexample: String,
    ) : Verdict

    /** No prover could decide the obligation, for the given [reason]. */
    data class Undischarged(
        val reason: String,
    ) : Verdict
}

/** A proof obligation, named like Rodin names it (such as `inc/inv1/INV`), and its verdict. */
data class ProofObligation(
    val name: String,
    val location: Location,
    val verdict: Verdict,
)
