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

`runIde` is most useful with `src/test/testData/library/` opened as the project — a synthetic
24-document model that lints cleanly and is also the corpus the tests run against.

### The test model

`src/test/testData/library/` is deliberately synthetic; no client model is tracked here. The
cataloging side is ESDM's own [Your First Model by Hand](https://www.esdm.io/getting-started/your-first-model/)
example kept faithful to upstream, and the lending side grows it the way upstream's Modeling
Guide suggests — a second bounded context, a context mapping, a policy, an event handler, an
external system, and a Given-When-Then feature.

It must stay lint-clean:

```sh
./esdm lint -d src/test/testData/library    # no output, exit 0
./esdm view -d src/test/testData/library
```

Not yet covered, and worth adding as focused fixtures when the reference index lands:
`dynamic-consistency-boundary` and its free-standing (BC-scoped) events, `process-manager`,
`subdomain`, `entity`, `value-object`, `domain-service`, and `actor.backedBy`. The two
non-interchangeable `eventReference` variants in particular cannot be tested against this
fixture alone, since it only contains aggregate-owned events.

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
