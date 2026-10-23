import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
}

group = "com.kevshupp.kevmusic"
// Versión del PC, independiente de la app Android. publicar.sh la sube y crea el tag desktop-v<versión>
version = "1.0.0"

// La app conoce su propia versión (para buscar actualizaciones) mediante un objeto generado
val generateBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val ver = project.version.toString()
    inputs.property("version", ver)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/kevshupp/kevmusic/desktop/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.kevshupp.kevmusic.desktop\n\nobject BuildInfo {\n    const val VERSION = \"$ver\"\n}\n"
        )
    }
}
sourceSets.main { kotlin.srcDir(generateBuildInfo) }

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jmdns:jmdns:3.6.4")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    // MPRIS (teclas multimedia y applet de sonido de GNOME/Cinnamon) por D-Bus
    implementation("com.github.hypfvieh:dbus-java-core:5.2.2")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.2")
}

compose.desktop {
    application {
        mainClass = "com.kevshupp.kevmusic.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "kevmusic-desktop"
            packageVersion = project.version.toString()
            description = "Control remoto de escritorio para KevMusicPlayer"
            vendor = "kevshupp"
            modules("java.naming", "jdk.unsupported", "jdk.security.auth", "jdk.net")
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                menuGroup = "AudioVideo"
                appCategory = "Audio"
                shortcut = true
                debMaintainer = "kevshupp@users.noreply.github.com"
            }
        }
    }
}
