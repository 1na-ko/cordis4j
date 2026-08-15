# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - 2026-08-15

### Added

- Annotation-mediated injection (paper Section 6.4, decision D21): `@Inject(qualifier)` fields
  assembled by `Injects.injectFields(ctx, instance)` into one reactive declaration - populated as
  activation-time snapshots when every field key resolves, cleared on withdrawal or retirement,
  refilled on re-satisfaction; assembly fails fast on static/final/primitive fields; an instance
  without annotated fields is a no-op (T24). Zero-dependency (JDK reflection only).
- Design contract v2.1: decision D21 appended, boundary semantics 23, API contract section for
  `Inject`/`Injects`.
- `cordis4j-langchain4j` (new module): the LangChain4j tool bridge. `CordisToolRegistry` watches
  declared `CordisTool` service keys through `Context.inject`, so a session's tool set follows the
  reactive-coeffect lifecycle - a tool appears when its plugin loads, vanishes on hot unload, and
  is replaced on re-provision; handles execute the current binding; a broken specification is
  routed like a failed activation (D14); listener failures are isolated. `SessionToolDemo` shows
  mid-session tool load/unload/swap, including a tool whose dependency is annotation-injected
  (composing D21). Depends on langchain4j-core only - no model provider, runs offline (T25).
- `docs/design/hmr-evaluation.md` (+ zh-CN): the bytecode-level HMR evaluation against paper
  Section 5.2.2 - pf4j (3.15.0), OSGi (Core Release 9), and ModuleLayer compared on introduce/
  retract/GC-eviction, isolation, and model conflict with the fiber paradigm; recommends a
  zero-dependency custom ClassLoader engine as stage 1 and ModuleLayer as an optional stage 2,
  rejects OSGi and pf4j.
- `cordis4j-hmr` (new module): bytecode-level hot module replacement (paper Section 5.2.2, stage 1
  of the evaluation). `BytecodePluginLoader` loads a plugin jar into its own class loader (unique
  `Plugin` implementation discovered or named explicitly); `PluginHandle` observes collection and
  closes the loader on detach (the jar's file handle is released immediately, so jars can be
  rewritten on Windows); `PluginClassRegistry` evicts and observes entries by id;
  `HotReloadingLoader` bridges the core `Loader`'s transactional reconcile - a reload re-imports
  the jar, swaps the stale entry's fiber, and rolls back on failure, after which the replaced code
  becomes garbage-collectable (T26).
- `cordis4j-spring` (new module): the Spring integration. `ContextFactoryBean` exposes a cordis4j
  Context as a Spring bean (created or wrapped) and disposes it on container close;
  `CordisServiceRegistrar` provides `@CordisService` beans into the container's Context after
  initialization and withdraws them in reverse provisioning order on shutdown; without a Context
  bean the integration stays dormant. Main code depends on spring-beans only (T27).
- `cordis4j-inject-processor` (new module): compile-time generation for annotation injection
  (paper Section 6.4, compile-time metaprogramming). The processor validates `@Inject` fields at
  compile time (public top-level class in a named package; non-static/final/primitive/private
  fields) and emits a zero-reflection injector per class through the new `Injects.FieldTarget`
  accessor shape (core) - no reflection and no `opens` at runtime, at the cost of field
  visibility (T28).
- `docs/design/quarkus-evaluation.md` (+ zh-CN): the Quarkus integration evaluation - plain CDI
  producers, a CDI portable extension, and a full Quarkus extension compared; recommends a plain
  CDI module mirroring cordis4j-spring when a concrete deployment needs it, deferred for now.
- Event dispatch modes (core, decision D22, contract v2.2): `on(..., prepend)`, `once` (fires
  exactly once then unregisters), a function-shaped `fold` listener list, and the `bail`
  (short-circuit on the first non-null result) and `waterfall` (fold non-null results) dispatch
  modes - the synchronous subset of upstream's dispatch modes per the parity baseline
  docs/design/upstream-parity.md (T29).
- `cordis4j-timer` (new module): reversible timers over the spawn model -
  `Timers.setTimeout`/`setInterval` are spawned tasks whose handles cancel them and whose owning
  plugin domains revert them, and `Timers.timeout` returns a future that completes exceptionally
  with CancellationException on interruption - the JVM form of @cordisjs/timer (T30).
- `docs/design/upstream-parity.md` (+ zh-CN): the alignment baseline against cordiverse/cordis
  (9 packages): every upstream capability classified as aligned / partial / intentional JVM
  difference / missing, with the P4 plan.
- Intercept-chain consumption (core, decision D23, contract v2.3): `Context.intercepts(key)`
  collects the interception metadata bound along the tree root-first, nearest-last - the raw
  chain, unmerged, whose nearer-wins monoid is exactly `interceptOf` - the Java form of
  upstream's `Service.resolveConfig` (T31).
- Registry view (core, decision D24, contract v2.4): `Context.services()` snapshots the bindings
  this context provides - ancestors excluded, keyed by the effective store key - the typed form
  of upstream's registry enumeration (T32).
- Loader composition DSL (core, decisions D25/D26, contract v2.5): `ComponentSpec` trees
  (Entry/Group/Isolate/Include) flattened by `Loader.reconcileTree` into per-entry load
  contexts reconciled through the D18 engine - groups prefix children ids with ':', isolation
  realms load into derived contexts (disposed once their entries all unload), and includes
  inline another source resolved against the base directory; `Context.baseUrl()`/
  `withBaseUrl(path)` provide the base-directory derivation (T33).
- HMR isolation boundaries (decision D27, contract v2.6): the stage-1 parent-delegation model is
  documented in docs/design/hmr-evaluation.md section 5 and in the cordis4j-hmr module itself
  (BytecodePluginLoader javadoc, module README) - host classes win, no per-plugin dependency
  versions, no module encapsulation - and pinned by T34; the child-first and ModuleLayer upgrades
  are evaluated in docs/design/hmr-isolation-evaluation.md (+ zh-CN) and reserved until a real
  requirement appears.
- Spring lifecycle coverage (T35): close cascades to the session context while the root survives,
  repeated container cycles (prototype beans included) leak nothing, and closing a container
  drains @CordisService dependents with their teardowns still resolving the withdrawn binding
  (boundaries 13/14).

### Fixed

- Reference discipline of fiber handles (core): a disposed plugin handle now releases its fiber
  reference, so a retired fiber - and with it the plugin instance and, in a bytecode-level reload,
  its class loader - becomes collectable even while the ambient scope that tracked the handle
  still lives. Previously the handle's closure pinned the fiber for the context's lifetime, which
  leaked every plugin ever unloaded from a long-lived context. Behavior is unchanged; the handle
  is a static class because anonymous classes capture constructor parameters into synthetic final
  fields that would re-pin the fiber. Regression test: `PluginUnloadReleaseTest`.
- cordis4j-spring withdrawal ordering: `CordisServiceRegistrar` now implements SmartLifecycle and
  withdraws its bindings in the container's stop phase, before any bean is destroyed - previously
  the Context bean could dispose first, so a dependent's teardown could no longer resolve the
  withdrawn binding (core boundary 14). The registrar depends on spring-context for
  SmartLifecycle; covered by T35.

## [0.2.0] - 2026-08-14

Full coverage of the paper's core-library semantics (Sections 3-5, Algorithms 1-6): reactive
coeffects, the withdrawal drain, the fiber lifecycle with inertia, virtual-thread asynchrony,
and the declarative loader. Design contract bumped to v2.0 (decisions D11-D20).

### Added

- Reactive coeffects (paper Algorithm 3): `Context.inject` (4 overloads) declares a fiber that
  activates when every dependency resolves, unloads reactively when a relied binding is
  withdrawn, and re-activates when the dependency returns; the callback's returned Disposable
  joins the fiber domain (T11).
- Withdrawal drain (paper Algorithm 5, Theorem 63): unloading a provider withdraws its supplies
  first - every dependent, including one still activating (the chained unload of inertia),
  unloads before any of the provider's own effects revert, and each dependent's teardown still
  resolves the withdrawn binding (T12, D20).
- Supply uniqueness (paper Section 4.2): `SupplyConflictException` when two distinct active
  fibers supply one store key; ambient provides keep administrator-overwrite semantics (T13).
- Declaration mediation (paper Algorithm 6): declarative fibers resolve only their declared keys
  and own supplies - `InactiveAccessException` otherwise; plain plugins stay unrestricted (T14).
- Failure routing (paper Section 4.3.4): failed activations revert the partial domain, are
  recorded and logged, never propagate to siblings or triggers, and never retry (T15).
- Asynchrony (paper Sections 4.3.2-4.3.3): `pluginAsync` runs effect functions on virtual
  threads and waits for landing (inertia); `spawn` runs long tasks whose handles interrupt and
  join them (starting a task is a revertible effect); `currentFiber()` exposes the guard with
  `isDiverted`/`checkDiverted` (DivertedException); spawned tasks inherit their spawner's fiber
  (T19, T20).
- Events: supertype dispatch (isInstance) and per-listener filters; strict registration order
  within one context (T17).
- Interception metadata monoid (paper Section 5.1.2): `InterceptMetadata` merges along the chain
  root-to-lookup, nearer-wins; mixed kinds keep nearest-wins (T18).
- Declarative loader (paper Section 5.2.1 / Algorithm 10, configuration level): `LoaderConfig`
  and `ComponentEntry` with id-keyed diff, instance identity as the version, transactional
  reconcile with rollback to the previous set, and reverse-order disposal (T21).

### Fixed

- Same-instance duplicate provide no longer breaks overwrite semantics: removal disposables
  carry a registration token (T23).
- Service lookups fail fast on key/type mismatches (ClassCastException at provide time).
- Context-tree concurrency: registry state is internally locked with a one-direction lock order;
  user code (effect functions, teardowns, service hooks) runs outside the monitors; concurrent
  provide/get smoke-tested (T22).

### Changed

- Design contract v2.0: decision log D11-D20, updated deviations, boundary semantics 13-22,
  four-state lifecycle model (INACTIVE/LOADING/ACTIVE/UNLOADING) with inertia.

## [0.1.0] - 2026-08-14

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
