import net.edditech.esdm.build.MergeEsdmSchemas
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Platform 252 runs on Java 21. Building with a newer JDK would emit class files
// no IDE below 2026.2 can load; see build-number-ranges in the SDK docs.
kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Deliberately the *Community* build: IC physically lacks Ultimate-only
        // classes, so the compiler stops us reaching for APIs that would break
        // GoLand/WebStorm/PyCharm. Unified 2025.3+ distributions lost that guard.
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())

        bundledPlugin("org.jetbrains.plugins.yaml")

        // The JSON Schema engine lives here. JSON was extracted from the
        // platform into its own plugin in 2024.3; omitting this is the most
        // common cause of "requires plugin com.intellij.modules.json" reports.
        // Both IDs confirmed against `./gradlew printBundledPlugins`.
        bundledPlugin("com.intellij.modules.json")

        testFramework(TestFrameworkType.Platform)
    }
}

// The bundled schema is generated, never hand-edited: refresh the vendored
// sources with `./esdm add-schema` and rebuild.
// The resource root is declared, not derived by walking parents off the output
// path: getting that wrong would relocate the resource silently, and the only
// symptom would be getResourceFile() returning null at IDE runtime — no schema,
// no build failure.
val generatedSchemaRoot = layout.buildDirectory.dir("generated/esdm")

val mergeEsdmSchemas = tasks.register<MergeEsdmSchemas>("mergeEsdmSchemas") {
    schemasDirectory = layout.projectDirectory.dir("schemas")
    // Must stay in step with SCHEMA_RESOURCE in EsdmSchemaProviderFactory.
    outputFile = generatedSchemaRoot.map { it.file("schemas/esdm.schema.json") }
}

sourceSets.main {
    resources.srcDir(mergeEsdmSchemas.map { generatedSchemaRoot })
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // JetBrains: "It's highly recommended not to set this attribute."
            // The Gradle plugin defaults it to "<MAJOR>.*", so unset explicitly —
            // Marketplace can cap compatibility later without a republish.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // `recommended()` already covers the latest release of each major
            // version in our compatibility range. The cross-IDE `select` below
            // is bounded on BOTH ends deliberately: left open, it would resolve
            // every release of four products from 252 to whatever ships next,
            // so the CI download grew without anyone changing a line of code.
            recommended()
            select {
                types = listOf(
                    IntelliJPlatformType.GoLand,
                    IntelliJPlatformType.WebStorm,
                    IntelliJPlatformType.PyCharm,
                    IntelliJPlatformType.PhpStorm,
                )
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
                untilBuild = providers.gradleProperty("pluginVerifyUntilBuild").get()
            }
        }
    }
}
