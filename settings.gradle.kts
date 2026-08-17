import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "esdm-jetbrains-plugin"

pluginManagement {
    plugins {
        // Keep in step with the Kotlin version bundled by the target platform;
        // shipping a newer stdlib than the IDE provides is a classic breakage.
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    // Provisions the JDK 21 toolchain automatically if it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
