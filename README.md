# Cordis4j

[![CI](https://github.com/1na-ko/cordis4j/actions/workflows/ci.yml/badge.svg)](https://github.com/1na-ko/cordis4j/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/require-JDK%2021+-blue.svg)](pom.xml)

[中文文档](README.zh-CN.md)

**Cordis4j** is the JVM implementation of the [Cordis](https://github.com/cordiverse/cordis)
meta-framework of *spatiotemporal composability* — the kernel beneath
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness):

- **Temporal** — every context mutation carries a tracked inverse; unloading reverts everything
  in LIFO order (revertible effects).
- **Spatial** — dependencies are declared and *reactively* resolved: a component activates when
  its declaration is satisfied, unloads — drained in dependency order — when a provider is
  withdrawn, and re-activates when the dependency returns (reactive coeffects).

```java
Context ctx = Contexts.create();

// A reactive component: online only while the database plugin is loaded
ctx.inject(Database.class, (c, db) -> {
  c.provide(new Cache(db));                    // reverts automatically on withdrawal
  return Disposables.of(() -> log("cache offline"));
});

Disposable db = ctx.plugin(new DatabasePlugin());   // → cache activates
db.dispose();                                       // → dependents drain first, then the provider
```

> Status: v0.2.0 - **incubating**. Semantics follow the formal model in
> [A Programming Paradigm for Spatiotemporal Composability](https://github.com/cordiverse/paper)
> (Sections 3-5, Algorithms 1-6); the API is a Java re-imagining, not a line-by-line port of the
> TypeScript code. See [docs/design-contract.md](docs/design-contract.md) for the frozen contract
> and decision log.

## What this is (and is not)

**Is** - a zero-dependency, semantics-faithful JVM implementation of the Cordis paper, aimed at
long-running hosts that must rewire themselves at runtime: unload a live component and its side
effects are reverted by construction; withdraw a provider and every dependent drains before it,
in dependency order, and re-activates when it returns. The paradigm is proven in production by
[Koishi](https://koishi.chat) (4000+ community plugins) and by the
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) kernel.

**Is not** - a Spring competitor. If your components never change after startup, static DI
(Spring, Guice, Dagger, ...) already covers you, and Cordis4j would merely be a smaller DI
container. Its value appears exactly where static wiring cannot express the requirement: runtime
unloading with guaranteed reversion, and reactive re-wiring of dependents when providers appear,
disappear, or are replaced.

## Requirements

- JDK 21+
- Maven 3.9+

## Modules

- `cordis4j-core` — zero-dependency core library (JPMS module `io.cordis4j.core`): effects,
  reactive coeffects, the fiber lifecycle, virtual-thread asynchrony, and the declarative loader.
- `cordis4j-demo` — end-to-end demos.
- `cordis4j-langchain4j` — LangChain4j tool bridge: session tools that load, hot-unload, and swap
  with their plugins (langchain4j-core only; no model provider, runs offline).
- `cordis4j-hmr` — bytecode-level hot module replacement: loads plugin jars into per-plugin class
  loaders, swaps fibers transactionally on reload, and collects the replaced code.
- `cordis4j-spring` — Spring integration: a Context bean and @CordisService beans whose bindings
  follow the container's lifecycle (spring-beans only; zero code changes to the core).
- `cordis4j-inject-processor` — compile-time generation for annotation injection: validates
  @Inject fields at compile time and emits a zero-reflection injector per class.
- `cordis4j-timer` — reversible timers over the spawn model: one-shot and periodic callbacks are
  effects whose inverse stops them.

## Feature map (paper → Cordis4j)

| Paper construct | Status | Where |
|---|---|---|
| Revertible effects, LIFO accumulator (§3.1, Alg. 1) | ✅ | `EffectScope`, `Disposable` |
| Reactive coeffects: satisfaction, notify, refresh (§3.2, Alg. 3) | ✅ | `Context.inject` |
| Event dispatch modes: prepend, once, bail, waterfall (upstream parity) | ✅ | `Context.on/once/fold/bail/waterfall` (D22) |
| Annotation-mediated injection (§6.4) | ✅ | `@Inject`, `Injects.injectFields`; compile-time generation in `cordis4j-inject-processor` |
| Withdrawal drain, provider-teardown ordering (§4.3.1, Th. 63) | ✅ | automatic on unload |
| Supply uniqueness (§4.2) | ✅ | `SupplyConflictException` |
| Declaration mediation / capability access (Alg. 6) | ✅ | enforced in declarative fibers |
| Failure routing, no-retry (§4.3.4) | ✅ | recorded, logged, never propagated |
| Inertia: chained unload of in-flight fibers (§4.3.3) | ✅ | unload waits for landing |
| Asynchrony on virtual threads + guard/divert (§4.3.2) | ✅ | `pluginAsync`, `spawn`, `currentFiber` |
| Isolation realms + interception metadata monoid (§5.1.2) | ✅ | `isolate`, `InterceptMetadata` |
| Declarative loader, id-keyed diff, transactional reload (§5.2.1, Alg. 10) | ✅ | `Loader` |
| Bytecode-level hot module replacement (§5.2.2) | ✅ | `HotReloadingLoader` in `cordis4j-hmr` |
| LangChain4j tool bridge (ecosystem) | ✅ | `CordisToolRegistry` in `cordis4j-langchain4j` |
| Spring integration (ecosystem) | ✅ | `ContextFactoryBean` in `cordis4j-spring` |

## Quickstart & demos

See `cordis4j-demo/src/main/java/io/cordis4j/demo/`:

- `QuickStart` — fork a session, events, dispose reverts the subtree.
- `ReactiveCompositionDemo` — a cache that follows its database plugin on- and offline.
- `MultiTenantDemo` — per-tenant realm isolation (the session-sandbox pattern).
- `HotReloadDemo` — configuration reconcile with transactional rollback.
- `AgentHarnessDemo` — everything-is-a-plugin: reactive tools, virtual-thread agent loop,
  guards, and whole-session teardown in one dispose.

Run the default demo (`QuickStart`) with:

```console
mvn install -DskipTests    # install cordis4j-core into the local repository once
mvn -pl cordis4j-demo exec:java
```

Any of the others with `-Dexec.mainClass=io.cordis4j.demo.<DemoName>`.

`cordis4j-langchain4j` ships `SessionToolDemo` (agent tools that load, hot-unload, and swap
mid-conversation); run it with `mvn -pl cordis4j-langchain4j exec:java`.

## Build & quality gates

```console
mvn verify   # enforcer + spotless + tests (T1-T32, 123 tests) + jacoco (>= 85%) + javadoc + dependency analysis
```

## Roadmap

- **P4 (upstream parity)** — the remaining items of docs/design/upstream-parity.md: intercept-chain
  config resolution (the Java form of `Service.resolveConfig`) and the loader composition DSL
  (group/isolate/tree/include) with typed registry views.
- **P3** — the ModuleLayer HMR variant (stage 2 of `docs/design/hmr-evaluation.md`; stage 1, the
  zero-dependency ClassLoader engine, has landed as `cordis4j-hmr`) and the Quarkus integration
  (evaluated and deferred in `docs/design/quarkus-evaluation.md`). The other P3 items have landed:
  annotation injection (runtime `@Inject`/`Injects` and compile-time
  `cordis4j-inject-processor`), LangChain4j (`cordis4j-langchain4j`), and Spring
  (`cordis4j-spring`).

## Contributing

A personal project, maintained in spare time - issues and PRs are welcome (see
[CONTRIBUTING.md](CONTRIBUTING.md) for building, testing, and the PR workflow). English docs
are canonical; Chinese translations follow along in `README.zh-CN.md` and `docs/zh/`.

## Credits

Cordis4j's semantics are based on the Cordis paper
([github.com/cordiverse/paper](https://github.com/cordiverse/paper)) and the reference
implementations [cordiverse/cordis](https://github.com/cordiverse/cordis) and
`@deepseek-ai/cordis` - both MIT-licensed code. Cordis4j itself is released under the
[MIT License](LICENSE).
