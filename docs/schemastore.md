# Contributing ESDM to SchemaStore

[SchemaStore](https://www.schemastore.org/) is the catalogue VS Code, Neovim and several other
editors consult to find a JSON Schema for a given filename. It has **no ESDM entry** — checked
against all 1418 entries — so `*.esdm.yaml` gets no validation anywhere outside this plugin.

Adding one is a single pull request and would give ESDM users schema validation and completion
in editors this plugin will never reach.

## Before doing it: whose schema is this?

The obvious candidate to submit is `build/generated/esdm/schemas/esdm.schema.json`, which this
build produces. Two reasons to pause first.

**It is a derived artifact, not upstream's.** The build lifts YAML comments into `description`
fields and restructures the top level to dispatch on `apiVersion` with `if`/`then`. Both changes
exist because of specific IntelliJ behaviour. Publishing it to SchemaStore would make *our*
transformation the canonical ESDM schema for every other editor, which is more than we should
decide on our own.

**It is thenativeweb's schema.** MIT licensed, and the generated file carries the notice — so
redistribution is permitted. But the courteous and probably better path is to offer the entry
[upstream](https://github.com/thenativeweb/esdm) so ESDM owns its own catalogue entry, rather
than edditech owning it on their behalf.

**Suggested order:** open an issue on `thenativeweb/esdm` offering the SchemaStore entry and the
JSON conversion. If upstream is not interested, submit it from here with clear attribution.

## The mechanics, when the time comes

SchemaStore hosts schemas that have no stable public home. Fork
[SchemaStore/schemastore](https://github.com/SchemaStore/schemastore) and add two things:

**1. The schema** at `src/schemas/json/esdm.json` — the merged document this build generates:

```sh
./gradlew mergeEsdmSchemas
# -> build/generated/esdm/schemas/esdm.schema.json
```

**2. The catalogue entry** in `src/api/json/catalog.json`, kept in alphabetical order:

```json
{
  "name": "ESDM",
  "description": "Event-Sourced Domain Modeling — domains, bounded contexts, aggregates, commands and events",
  "fileMatch": ["**/*.esdm.yaml"],
  "url": "https://json.schemastore.org/esdm.json"
}
```

`fileMatch` uses the suffix the core schema publishes as `x-esdm-file-suffix`, so it stays
correct if the layout convention changes.

## What it would not fix

SchemaStore only carries the schema. Everything this plugin adds on top — the linter inline,
navigation, rename, and completion that discriminates on `kind` where the schema engine cannot —
stays specific to JetBrains IDEs.
