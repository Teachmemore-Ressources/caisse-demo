import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "fr.demo.caisse"
version = "1.3.3"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.xerial:sqlite-jdbc:3.51.3.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "fr.demo.caisse.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "caisse-demo"
            packageVersion = "1.3.3"
            description = "Caisse enregistreuse de démonstration"
            // Modules Java nécessaires au runtime embarqué (jlink)
            modules("java.sql", "java.naming", "java.net.http", "jdk.crypto.ec", "jdk.unsupported")
            // Icône de l'application (dessinée pour ce projet) : utilisée par le lanceur et le raccourci
            linux {
                iconFile.set(project.file("packaging/icons/caisse-demo-512.png"))
            }
        }
    }
}
