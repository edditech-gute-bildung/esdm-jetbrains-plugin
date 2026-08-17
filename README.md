# esdm-jetbrains-plugin
A Jetbrains-Suite Plugin for working with ESDM (Event Sourced Domain Modeling) files.

## Development

Requires a **JDK 21** — the target platform (build `252`) runs on Java 21, and building with a
newer JDK produces class files no IDE below 2026.2 can load.

```sh
./gradlew buildPlugin     # -> build/distributions/*.zip
./gradlew check           # tests
./gradlew runIde          # sandbox IDE with the plugin loaded
./gradlew verifyPlugin    # binary compatibility against the supported IDEs
```

The first run downloads IntelliJ IDEA Community 2025.2.6.3 (~1 GB) into the Gradle cache.

`runIde` is most useful with the sample model in `example/esdm-modell/` opened as the project.

### Why Community as the compile target

Building against `IC` rather than the unified `IU` distribution is deliberate: Community builds
physically lack Ultimate-only classes, so the compiler stops us reaching for APIs that would
break the plugin in GoLand, WebStorm, PyCharm and the rest. IntelliJ IDEA stopped shipping
separate `IC` builds with 2025.3, so this guard rail only exists while we target `252`. Once the
floor moves past it, `verifyPlugin` becomes the only line of defence.

### The `esdm` binary

Not committed — it is platform-specific (~8 MB) and pinned per project. The plugin discovers it
at `./esdm`, then on `PATH`, with an override in settings. To get one:

```sh
curl -O https://esdm.s3.fr-par.scw.cloud/0.14.0/esdm-darwin-arm64
mv esdm-darwin-arm64 esdm && xattr -d com.apple.quarantine esdm && chmod a+x esdm
```

# ESM Infos
Main site: [https://esdm.io](https://esdm.io).
Docs: [https://github.com/thenativeweb/esdm/tree/main/documentation/docs](https://github.com/thenativeweb/esdm/tree/main/documentation/docs)
Core Schema: [https://github.com/thenativeweb/esdm/blob/main/schema/core/v1.yaml](https://github.com/thenativeweb/esdm/blob/main/schema/core/v1.yaml)
Given-When-Then Schema: [https://github.com/thenativeweb/esdm/blob/main/schema/given-when-then/v1.yaml](https://github.com/thenativeweb/esdm/blob/main/schema/given-when-then/v1.yaml)
