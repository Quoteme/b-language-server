plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin.
    kotlin("jvm") version "2.2.20"
    // Generates the JSON (de)serializers of classes annotated with @Serializable.
    kotlin("plugin.serialization") version "2.2.20"
    idea
    // Apply the application plugin to add support for building a CLI application.
    application
    id("com.gradleup.shadow") version "9.2.2"
}

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots")

}



dependencies {
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

    // eclipse lsp implementation
    implementation("org.eclipse.lsp4j", "org.eclipse.lsp4j", "0.24.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-test-junit5
    testImplementation(kotlin("test-junit5"))

    // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
    testImplementation("org.junit.jupiter", "junit-jupiter-engine", "5.14.0")

    implementation(platform("de.hhu.stups:prob-java-bom:4.16.1"))
    implementation("de.hhu.stups", "prob-java")
    // Parser for Event-B in Camille notation
    implementation("de.hhu.stups:eventbstruct:2.16.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    // Each test starts ProB processes of its own; one test JVM at a time keeps their number bounded.
    maxParallelForks = 1
    maxHeapSize = "2g"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("b.language.server.AppKt") // The main class of the application
}

