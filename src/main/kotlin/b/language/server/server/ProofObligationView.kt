package b.language.server.server

import b.language.server.analysis.ProofObligation
import b.language.server.analysis.Verdict
import b.language.server.client.SerializedNames
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName

/** A proof obligation as the client lists it; lsp4j serialises it with Gson. */
data class ProofObligationView(
    val uri: String,
    val line: Int,
    val character: Int,
    val name: String,
    @JsonAdapter(SerializedNames::class)
    val status: Status,
    val detail: String,
) {
    enum class Status {
        @SerializedName("discharged")
        DISCHARGED,

        @SerializedName("disproved")
        DISPROVED,

        @SerializedName("undischarged")
        UNDISCHARGED,
    }

    companion object {
        fun of(obligation: ProofObligation): ProofObligationView {
            val verdict = obligation.verdict
            val status =
                when (verdict) {
                    Verdict.Discharged -> Status.DISCHARGED
                    is Verdict.Disproved -> Status.DISPROVED
                    is Verdict.Undischarged -> Status.UNDISCHARGED
                }
            val detail =
                when (verdict) {
                    Verdict.Discharged -> ""
                    is Verdict.Disproved -> verdict.counterexample
                    is Verdict.Undischarged -> verdict.reason
                }
            val at = obligation.location
            return ProofObligationView(at.file.toURI().toString(), at.start.line, at.start.column, obligation.name, status, detail)
        }
    }
}
