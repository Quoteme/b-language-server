// Applies Dokka (HTML, with Mermaid diagrams) without making it part of the build:
//   ./gradlew --init-script gradle/dokka.init.gradle.kts dokkaGeneratePublicationHtml
// The result is in build/dokka/html; Module.md holds the module and package documentation.
initscript {
    repositories { gradlePluginPortal() }
    dependencies { classpath("org.jetbrains.dokka:dokka-gradle-plugin:2.1.0") }
}

/** An npm package, checked against the integrity hash the registry publishes for it. */
data class NpmPackage(
    val url: String,
    val integrity: String,
)

// The Mermaid plugin bundles Mermaid 10, which lays out class and state diagrams with curved,
// crossing edges. Mermaid 12 with the ELK layout routes every edge at right angles instead.
val mermaid =
    NpmPackage(
        "https://registry.npmjs.org/mermaid/-/mermaid-12.2.0.tgz",
        "sha512-w7nr/SFrLO7BaVTs3dQTvYSV6nYSBj0Yy9DjBhsIJtAcHqRzXSq4Pg32ujHZ/AdWsAMNEvvhbm5dt9E2tOiHzw==",
    )
val mermaidElk =
    NpmPackage(
        "https://registry.npmjs.org/@mermaid-js/layout-elk/-/layout-elk-1.0.1.tgz",
        "sha512-6KzQTCAy4lSlETy5fBOYyUD0JQtZk/ihacVHSmAyo00hW7REOhKQGjNIxVwAnI+HtJdfpGnSMxkp9qnJDijppw==",
    )

rootProject {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        plugins.apply(org.jetbrains.dokka.gradle.formats.DokkaHtmlPlugin::class.java)
        dependencies.add("dokkaPlugin", "com.glureau:html-mermaid-dokka-plugin:0.6.0")
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            moduleName.set(rootProject.name)
            dokkaSourceSets.register("main") {
                sourceRoots.from("src/main/kotlin", "src/main/java")
                classpath.from(configurations.named("compileClasspath"))
                includes.from("Module.md")
                jdkVersion.set(17)
            }
            pluginsConfiguration.withType<org.jetbrains.dokka.gradle.engine.plugins.DokkaHtmlPluginParameters>().configureEach {
                customStyleSheets.from("docs/retro.css")
                // The header logo; docs/retro.css points Dokka at it. Dokka copies assets into images/.
                file("docs/icon.png").takeIf { it.isFile }?.let { customAssets.from(it) }
                footerMessage.set("b-language-server &middot; best viewed in any browser at 1024&times;768")
            }
        }

        val site = layout.buildDirectory.dir("dokka/html")
        val downloads = layout.buildDirectory.dir("mermaid")
        val shim = file("docs/mermaid-elk.js")
        val gradleProject: Project = this@rootProject

        /** The package's archive, downloaded once and checked against its integrity hash. */
        fun fetch(npm: NpmPackage): File {
            val archive = downloads.get().file(npm.url.substringAfterLast('/')).asFile
            if (!archive.isFile) {
                archive.parentFile.mkdirs()
                java.net.URI(npm.url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-512").digest(archive.readBytes())
            val actual = "sha512-" + java.util.Base64.getEncoder().encodeToString(digest)
            if (actual != npm.integrity) {
                archive.delete()
                throw GradleException("${npm.url} does not match its integrity hash: expected ${npm.integrity}, got $actual")
            }
            return archive
        }

        val installMermaid =
            tasks.register("dokkaInstallMermaidElk") {
                description = "Replaces the Mermaid of the Dokka Mermaid plugin with Mermaid 12 and the ELK layout."
                inputs.file(shim)
                doLast {
                    val html = site.get().asFile
                    val mermaidArchive = gradleProject.tarTree(gradleProject.resources.gzip(fetch(mermaid)))
                    val elkArchive = gradleProject.tarTree(gradleProject.resources.gzip(fetch(mermaidElk)))
                    val mermaidJs = mermaidArchive.matching { include("package/dist/mermaid.min.js") }.singleFile
                    File(html, "dokka-mermaid.js").writeText(mermaidJs.readText() + "\n" + shim.readText())
                    // ELK is an ES module that loads its chunks by relative paths, so the layout of dist is kept.
                    gradleProject.copy {
                        from(elkArchive) {
                            include("package/dist/mermaid-layout-elk.esm.min.mjs")
                            include("package/dist/chunks/mermaid-layout-elk.esm.min/*.mjs")
                            eachFile { path = path.removePrefix("package/dist/") }
                            includeEmptyDirs = false
                        }
                        into(File(html, "mermaid-elk"))
                    }
                }
            }
        tasks.named("dokkaGeneratePublicationHtml") { finalizedBy(installMermaid) }
    }
}
