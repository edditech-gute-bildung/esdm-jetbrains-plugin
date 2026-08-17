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
            // `recommended()` covers IntelliJ IDEA across the supported range.
            //
            // For the other products we verify only the ENDS of the range, not
            // every release in it. A `select` spanning 252 to latest across four
            // products resolved 17 IDEs — roughly 17 GB of CI downloads, growing
            // with every JetBrains release. Binary incompatibilities show up at
            // the boundaries of a compatibility range; the versions in between
            // almost never add a finding the edges missed.
            recommended()

            val crossIdeProducts = listOf(
                IntelliJPlatformType.GoLand,
                IntelliJPlatformType.WebStorm,
                IntelliJPlatformType.PyCharm,
                IntelliJPlatformType.PhpStorm,
            )
            val oldest = providers.gradleProperty("pluginSinceBuild").get()
            val newest = providers.gradleProperty("pluginVerifyUntilBuild").get()

            // Oldest supported build...
            select {
                types = crossIdeProducts
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = oldest
                untilBuild = "$oldest.*"
            }
            // ...and the newest we have verified against. Raise deliberately as
            // new platform versions land.
            select {
                types = crossIdeProducts
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = newest.substringBefore(".*")
                untilBuild = newest
            }
        }
    }
}
