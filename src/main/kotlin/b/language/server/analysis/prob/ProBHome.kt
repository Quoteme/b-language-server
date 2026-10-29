package b.language.server.analysis.prob

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

class ProBHomeNotFound(
    home: ProBHome.Directory,
) : Exception(
        "searched at ${home.path.path} for prob but found nothing - make sure you point to the prob directory not to probcli.sh/probcli",
    )
