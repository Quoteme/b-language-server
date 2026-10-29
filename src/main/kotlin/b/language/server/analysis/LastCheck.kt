package b.language.server.analysis

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What the last check of each file found, for hovers and completions until the next check. */
class LastCheck<T : Any>(
    private val empty: T,
) {
    private val results = ConcurrentHashMap<File, T>()

    operator fun get(file: File): T = results[file.absoluteFile] ?: empty

    operator fun set(
        file: File,
        result: T,
    ) {
        results[file.absoluteFile] = result
    }

    fun forget(file: File) {
        results.remove(file.absoluteFile)
    }
}
