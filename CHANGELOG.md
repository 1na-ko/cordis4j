# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - unreleased

The first vertical slice: the frozen design contract and the zero-dependency core.

### Added

- Design contract (docs/design-contract.md): decision log D1-D10, API contract, paper mapping
  table, explicit deviation list, and boundary semantics (tests T1-T10 + edge cases fix each clause).
- Core library cordis4j-core (JPMS module io.cordis4j.core, Java 21, zero runtime dependencies):
  - Revertible effects: Context.effect() scopes with LIFO reversion, idempotent Disposable,
    failure aggregation via DisposeException (paper Section 3.1 / Algorithm 1).
  - Reactive coeffects: typed ServiceKey (type + realm qualifier) resolution along the context
    tree, provide/get/find, isolate realm derivation, intercept metadata table
    (paper Section 5.1.2).
  - Synchronous events with child-to-root bubbling and revertible registrations (decision D3).
  - Two-state plugin lifecycle with LIFO unload and cross-fiber cascade (paper Algorithm 4);
    Lifecycle seam reserved for the P2 inertial state machine.
  - Service.start/stop hooks as a documented extension (decision D9), minimal Logger with a
    java.util.logging adapter.
- End-to-end demo cordis4j-demo (demo.QuickStart, under 60 lines).
- Quality gates wired into mvn verify: enforcer (JDK 21+), spotless (google-java-format +
  license headers), JUnit 5 tests, JaCoCo (line coverage >= 85%), javadoc (doclint=all), and
  dependency analysis. GitHub Actions CI on push/PR.

## [Unreleased]
