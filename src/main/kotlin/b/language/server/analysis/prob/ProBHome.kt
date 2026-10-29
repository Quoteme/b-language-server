package b.language.server.analysis.prob

import b.language.server.analysis.AnalysisFailure
import java.io.File

/** Where the ProB installation is: prob-java's own, or a directory the user chose. */
sealed interface ProBHome {
    data object Default : ProBHome

    data class Directory(
        val path: File,
    ) : ProBHome

    companion object {
        private const val DEFAULT = "DEFAULT"

        /** The home named by the setting `probHome`: "DEFAULT" or a directory. */
        fun of(setting: String): ProBHome = if (setting == DEFAULT) Default else Directory(File(setting))
    }
}

/** Why ProB cannot be used. */
sealed interface ProBUnavailable : AnalysisFailure {
    data class HomeNotFound(
        val home: ProBHome.Directory,
    ) : ProBUnavailable {
        override val message: String
            get() =
                "searched at ${home.path.path} for prob but found nothing - make sure you point to the prob directory " +
                    "not to probcli.sh/probcli"
    }

    data class StartFailed(
        val home: ProBHome,
        val reason: String?,
    ) : ProBUnavailable {
        override val message: String get() = "wrong path to prob $home" + (reason?.let { ": $it" } ?: "")
    }
}
