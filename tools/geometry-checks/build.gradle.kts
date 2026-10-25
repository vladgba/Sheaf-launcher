plugins {
    kotlin("jvm") version "2.0.21"
    application
}
repositories { mavenCentral() }

// Desktop-only JSON implementation; Android supplies org.json in the app.
dependencies { implementation("org.json:json:20240303") }
kotlin { jvmToolchain(17) }
sourceSets.main {
    kotlin.srcDirs("src", "../../app/src/main/java/x/vladgba/sheaflauncher/model")
    kotlin.include("Model.kt", "BackupGeometryChecks.kt")
}
application { mainClass.set("BackupGeometryChecksKt") }
