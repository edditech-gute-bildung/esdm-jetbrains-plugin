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
