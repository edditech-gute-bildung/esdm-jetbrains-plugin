# Changelog

All notable changes to this project are documented here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The `[Unreleased]`
section is consumed by `patchPluginXml` and becomes the plugin's change notes
on JetBrains Marketplace, so write it for users, not for contributors.

## [Unreleased]

### Added

- Project scaffolding: Gradle 9.7.0, IntelliJ Platform Gradle Plugin 2.18.1,
  targeting IntelliJ IDEA Community 2025.2.6.3 (build `252`) on JDK 21.
- A synthetic `library` model under `src/test/testData/`, used both as the test
  corpus and as a sample project to open in the sandbox IDE.
- Schema validation for `*.esdm.yaml`. The ESDM schemas are bundled and applied
  automatically, so the per-document `# yaml-language-server: $schema=...`
  modeline is no longer needed.
- Completion that knows what kind of document you are writing: inside a
  `kind: aggregate` it offers `identifiedBy` and `state` rather than every
  property of every kind, and a `context-mapping`'s `type` offers the eight DDD
  mapping patterns. Required fields are listed first. Once a `type` is chosen,
  only that variant's fields are offered.
- `esdm lint` findings shown inline. The binary is discovered next to the
  project or on `PATH`, and can be pointed at explicitly under
  *Settings | Tools | ESDM*, along with the model root. A missing binary is not
  an error — the plugin simply stays quiet.
- Rule-scoped suppression: `# esdm-lint-disable <rule-id>` above a line, or
  `# esdm-lint-disable-file <rule-id>` anywhere in the document, with a quick fix
  to insert either. Suppressing one rule leaves the others reporting.
