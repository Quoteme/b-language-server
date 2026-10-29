package b.language.server

import arrow.core.getOrElse
import b.language.server.analysis.AnalysisDispatcher
import b.language.server.analysis.prob.ProBSession
import b.language.server.server.Server
import org.eclipse.lsp4j.launch.LSPLauncher
import java.net.ServerSocket
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val (input, output) =
        if ("--stdio" in args) {
            // Keep log output of ProB and its dependencies away from the JSON-RPC stream.
            val out = System.out
            System.setOut(System.err)
            System.`in` to out
        } else {
            val socket = ServerSocket(0)
            println("<${socket.localPort}> is the port; opening connection and listening")
            socket.accept().let { it.getInputStream() to it.getOutputStream() }
        }

    val session =
        ProBSession.start().getOrElse {
            System.err.println("ProB could not be started: ${it.message}")
            exitProcess(1)
        }
    val server = Server(AnalysisDispatcher(session))
    val launcher = LSPLauncher.createServerLauncher(server, input, output)
    // Connect the client before listening, so no request reaches the server without it.
    server.setRemoteProxy(launcher.remoteProxy)
    launcher.startListening().get()
}
