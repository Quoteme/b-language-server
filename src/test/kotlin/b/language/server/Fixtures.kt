package b.language.server

import b.language.server.project.Project
import b.language.server.project.classicB.ClassicBProject
import b.language.server.project.eventB.EventBProject
import java.io.File

/** The models in src/test/resources, as files and projects. */
object Fixtures {
    fun file(path: String): File = File("src/test/resources/$path").absoluteFile

    fun project(path: String): Project = file(path).let { Project.of(it, it.readText()) }

    fun eventB(name: String): EventBProject = file("eventb/$name.mch").let { EventBProject(it, it.readText()) }

    fun classicB(path: String): ClassicBProject = file(path).let { ClassicBProject(it, it.readText()) }
}
