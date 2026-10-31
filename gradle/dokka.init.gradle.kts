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

/** The project's page; the docs link to it from the header, the footer and every declaration. */
val repository = "https://github.com/quoteme/b-language-server"

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
                // Each declaration links to its source on the default branch, which the docs are published from.
                sourceLink {
                    localDirectory.set(file("src/main/kotlin"))
                    remoteUrl("$repository/blob/master/src/main/kotlin")
                    remoteLineSuffix.set("#L")
                }
            }
            pluginsConfiguration.withType<org.jetbrains.dokka.gradle.engine.plugins.DokkaHtmlPluginParameters>().configureEach {
                customStyleSheets.from("docs/retro.css")
                // The header logo; docs/retro.css points Dokka at it. Dokka copies assets into images/.
                file("docs/icon.png").takeIf { it.isFile }?.let { customAssets.from(it) }
                homepageLink.set(repository)
                footerMessage.set(
                    "b-language-server &middot; <a href=\"$repository\">source on GitHub</a> &middot; best viewed in any browser at 1024&times;768",
                )
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
        // Dokka links its own logo as the favicon of every page; docs/icon.png replaces it, if there is one.
        val icon = file("docs/icon.png")
        val useIconAsFavicon =
            tasks.register("dokkaUseIconAsFavicon") {
                description = "Makes docs/icon.png the favicon of the Dokka pages."
                onlyIf { icon.isFile }
                doLast {
                    site.get().asFileTree.matching { include("**/*.html") }.forEach { page ->
                        val text = page.readText()
                        val replaced =
                            text.replace(Regex("""<link href="([^"]*)images/logo-icon\.svg" rel="icon" type="image/svg">""")) {
                                """<link href="${it.groupValues[1]}images/icon.png" rel="icon" type="image/png">"""
                            }
                        if (replaced != text) page.writeText(replaced)
                    }
                }
            }
        tasks.named("dokkaGeneratePublicationHtml") { finalizedBy(installMermaid, useIconAsFavicon) }
    }
}
