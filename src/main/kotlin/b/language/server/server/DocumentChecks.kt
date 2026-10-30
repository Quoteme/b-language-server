package b.language.server.server

import b.language.server.analysis.Analysis
import b.language.server.analysis.Problem
import b.language.server.client.CommunicatorInterface
import b.language.server.config.Settings
import b.language.server.project.OpenDocuments
import b.language.server.project.Project
import org.eclipse.lsp4j.MessageType
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Checks documents under the client's settings for them, and publishes the problems found to the client. */
class DocumentChecks(
    /** The client's settings for a file. */
    private val settingsOf: (File) -> CompletableFuture<Settings>,
    private val communicator: CommunicatorInterface,
    private val analysis: Analysis<Project>,
    private val documents: OpenDocuments,
) {
    /** For each checked file, the problems last published, which may lie in the files it includes. */
    private val published = ConcurrentHashMap<File, List<Problem>>()

    /**
     * Checks a document under the client's settings for it.
     * @param adjust changes the client's settings for this check
     */
    fun check(
        file: File,
        adjust: (Settings) -> Settings = { it },
    ) {
        settingsOf(file).thenAccept { original ->
            val settings = adjust(original)
            communicator.setDebugMode(settings.debugMode)
            communicator.sendDebugMessage("settings are $settings", MessageType.Info)
            synchronized(analysis) { analysis.check(documents.projectOf(file), settings) }.fold(
                { failure ->
                    communicator.sendDebugMessage(failure.message, MessageType.Info)
                    communicator.showMessage(failure.message, MessageType.Error)
                },
                { problems ->
                    publish(file, problems)
                    communicator.showMessage("Evaluation done: ${problems.size} problem(s)", MessageType.Log)
                },
            )
        }
    }

    /** Model checks a document, and adds the problems found to those of its last check. */
    fun modelCheck(file: File): CompletableFuture<Unit> =
        settingsOf(file).thenApply { settings ->
            val report = synchronized(analysis) { analysis.modelCheck(documents.projectOf(file), settings) }
            publish(file, published[file].orEmpty() + report.problems)
            communicator.showMessage(report.summary, MessageType.Info)
            communicator.showMessage("Evaluation done: ${report.problems.size} problem(s) found by model checking", MessageType.Log)
        }

    /** Publishes the problems found by checking [file], and clears the files that no longer have any. */
    private fun publish(
        file: File,
        problems: List<Problem>,
    ) {
        val byTarget =
            problems.groupBy {
                it.location.file
                    .toURI()
                    .path
            }
        byTarget.forEach { (target, own) -> communicator.publishDiagnostics(target, own.map { it.toDiagnostic(SOURCE) }) }
        val stale =
            published[file]
                .orEmpty()
                .map {
                    it.location.file
                        .toURI()
                        .path
                }.toSet() - byTarget.keys
        stale.forEach { communicator.publishDiagnostics(it, emptyList()) }
        communicator.sendDebugMessage("invalidating old files $stale", MessageType.Info)
        published[file] = problems
    }

    private companion object {
        /** The source the client shows for the problems. */
        const val SOURCE = "ProB"
    }
}
