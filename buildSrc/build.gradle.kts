plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    // SnakeYAML is the only YAML parser that exposes comments on the node tree
    // (LoaderOptions.isProcessComments), which the schema transform depends on.
    implementation("org.yaml:snakeyaml:2.4")
    implementation("com.google.code.gson:gson:2.14.0")
}
