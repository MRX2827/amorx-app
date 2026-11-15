import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.3"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.json:json:20250517")
}

compose.desktop {
    application {
        mainClass = "amorx.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Exe)
            packageName = "AmorX"
            packageVersion = providers.gradleProperty("desktopVersion").orElse("5.5.499").get()
            appResourcesRootDir.set(layout.buildDirectory.dir("desktop-resources"))
            windows {
                iconFile.set(project.file("packaging/AmorX.ico"))
                perUserInstall = true
                menu = true
                shortcut = true
            }
            includeAllModules = true
            macOS {
                bundleID = "com.amorx.desktop"
                dockName = "AmorX"
                iconFile.set(project.file("packaging/AmorX.icns"))
            }
        }
    }
}
