# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

Greenfield. The repo contains no plugin sources and no build system yet — only `README.md`, `LICENSE`, `.gitignore` (tracked), plus two untracked working aids:

- `esdm` — the ESDM CLI binary (macOS arm64, v0.14.0, revision `40462017…`). Not tracked; it is ~8 MB and platform-specific. Download instructions and other platform builds: `example/esdm-modell/README.md`.
- `example/esdm-modell/` — a complete real-world ESDM model (German domain, "RST") used as reference material. It has its own README describing the model, the tooling, and the visualizer.

When scaffolding the plugin (Gradle + IntelliJ Platform Gradle Plugin is the conventional choice): `.gitignore` currently contains a blanket `*.jar`, which would silently exclude `gradle/wrapper/gradle-wrapper.jar`. Add a negation before committing the wrapper.

## The esdm CLI

Run from the model directory, or point at one with `-d`:

```sh
./esdm lint -d example/esdm-modell                  # validate a model
./esdm lint --format json --color never             # machine-readable findings
./esdm lint --warnings-as-errors                    # warnings affect exit code
./esdm view --with-details                          # hierarchical text summary
./esdm view <domain>/<bounded-context>/<aggregate>  # summary of one subtree
./esdm glossary                                     # ubiquitous language as Markdown
./esdm add-schema / update-schema                   # write/refresh local schemas/
./esdm version                                      # version + schema revision
```

`lint --format json` emits an array of `{ruleId, severity, message, location:{file,line,column}}` — the natural integration point for plugin inspections/annotators. Rule IDs are namespaced: `esdm/structure/*` (schema and reference integrity), `esdm/modeling/*` (modeling smells, mostly warnings), `esdm/gwt/*` (Given-When-Then extension), `esdm/system/*` (e.g. `schemas-directory-drift`).

Linting resolves references across the **whole model directory**, not per file — a single file linted in isolation reports `unresolved-reference` errors for everything outside it. Schemas are embedded in the binary; a local `schemas/` directory is optional, but if present it must match the binary's embedded revision or the linter rejects it (`esdm update-schema` fixes drift).

Known linter blind spots (documented in `example/esdm-modell/README.md`): `kind: entity` cross-references (`identifiedBy.field` into `schema`) are not checked, and `esdm view` omits entities entirely. Both are candidates for the plugin to cover.

## The ESDM file format

Read `example/esdm-modell/schemas/core/v1.yaml` before writing any parsing, indexing, or completion code — its header comments are the authoritative spec and expose conventions mechanically via `x-esdm-*` fields.

- Files are `*.esdm.yaml` (`x-esdm-file-suffix`); one file may hold multiple documents separated by `---` (`x-esdm-document-separator`).
- Every document has `apiVersion`, `kind`, `name`; `unevaluatedProperties: false` and `required: [apiVersion, kind, name]` at the top level. Names match `^[a-z][a-z0-9-]*$`.
- Validation dispatches on `apiVersion`. `schema.esdm.io/core/v1` → core schema; anything else → an extension schema at the sibling path `schema.esdm.io/<name>/v1`. The core `kind` enum is closed; extensions never inject kinds into it. `$id` is always `"https://" + apiVersion`.
- Core kinds: `domain`, `subdomain`, `bounded-context`, `context-mapping`, `aggregate`, `dynamic-consistency-boundary`, `command`, `event`, `event-handler`, `policy`, `process-manager`, `read-model`, `query`, `entity`, `value-object`, `domain-service`, `actor`, `external-system`.
- Extensions in use here: `given-when-then/v1` (`kind: feature`, files named `*.feature.esdm.yaml`) and `domain-storytelling/v1` (`kind: domain-story`).
- Per-kind shape lives in the top-level `allOf` of `if kind == X / then …` blocks, not in `properties` — a parser must walk that list to know a kind's fields.

### References (what navigation and completion must resolve)

Scoping is positional, and reference shape varies by consumer — this is the main source of subtlety:

- `scope` is a triple/pair of names: `{domain}`, `{domain, boundedContext}`, `{domain, boundedContext, aggregate}`, or `{domain, boundedContext, dynamicConsistencyBoundary}`.
- `eventReference` (used by `event-handler.handles`, `policy.handles`, `process-manager.startsWhen`/`reactions[].when`, `read-model.projections`, `dynamic-consistency-boundary.consults`) is either `{boundedContext, aggregate, event}` or `{boundedContext, event}` for free-standing DCB events. The two variants are not interchangeable — an event's shape is fixed by its own `scope`.
- `commandReference` (`policy.emits`, `process-manager.reactions[].emits`) mirrors that with `aggregate` or `dynamicConsistencyBoundary`.
- `command.publishes` uses **bare** event names, because the command's own scope already fixes the producer. Same for `actor.backedBy` and `event-handler.sideEffects.external-call.externalSystem`.
- Uniqueness of names within arrays, and cross-field references such as `aggregate.identifiedBy.field` → `state`, `process-manager.correlatedBy.field` → event payload, `process-manager.timers[].at` → `state`, are **modeling rules the JSON Schema does not enforce**. The linter covers some; the plugin will need its own index to cover them all.

Prose rules are first-class: `namedRule` (`{name, rule}`) for behavior the implementation must encode, `namedCondition` (`{name, condition}`) for predicates over state (currently only `process-manager.endsWhen`).

### Project layout convention

From `x-esdm-project-layout` in the core schema (a convention, not linter-enforced): one top-level directory per bounded context; BC-spanning artifacts (policies, process managers, event handlers, context mappings) in a sibling `integration/`; external systems inventoried flat in `external-systems.esdm.yaml` at the domain root; schemas under `schemas/{name}/{version}.yaml`. Within a BC, group by consistency unit. Tools that generate ESDM models should default to this layout.

## Reference material

- Docs: https://github.com/thenativeweb/esdm/tree/main/documentation/docs
- Core schema upstream: https://github.com/thenativeweb/esdm/blob/main/schema/core/v1.yaml
- ESDM Visualizer (Apache-2.0, event-modeling boards per aggregate): https://github.com/impierce/esdm-visualizer — `docker compose up -d` in `example/esdm-modell/`, then http://localhost:3000. Two known limitations are documented in that directory's README.

The example model, its README, and its comments are written in German; the schemas and the CLI are English. Match whatever language a file already uses when editing it.
