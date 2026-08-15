# Upstream Parity: cordiverse/cordis as the Alignment Anchor

> Canonical language: **English**. Chinese translation: docs/zh/upstream-parity.zh-CN.md.
> Status: baseline snapshot of cordiverse/cordis@main (August 2026, 9 packages) against Cordis4j.
> This document anchors the goal: Cordis4j should be as capable as the Cordis repository, while
> keeping the JVM's advantages. It is a living baseline, updated as either side evolves; the
> paper remains the semantic anchor where the upstream implementation and the paper diverge.

## 1. Method

- Read-only inventory of every upstream package and its public surface.
- Each upstream capability is classified: aligned / partially aligned / intentional difference
  (JVM advantage) / missing / not applicable.
- Missing items get a plan (P4-1...) and land in separate modules or contract entries; core stays
  zero-dependency.

## 2. Inventory

### 2.1 @cordisjs/core (the reference implementation of the paper's core library)

| Upstream | Cordis4j | Status |
|---|---|---|
| Built-in services behind a Proxy (`ctx.logger`, `ctx.events`, `ctx.reflect`, `ctx.registry`, string-named) | `ServiceKey<T>(type, qualifier)` + typed `get`/`find` (D1, D5) | Intentional difference: typed keys replace string names - the JVM advantage |
| `Context.root` / prototype-chain `extend(meta)` | `root()`, `fork()`, `isolate(type, realm)` | Aligned (Java form) |
| `Context.baseUrl` (config file directory) | absent | Missing; lands with the loader DSL (P4-2) |
| Events: `on` (prepend/priority options), `once`, dispatch modes `emit` / `bail` / `waterfall` / `parallel` / `serial` | synchronous `on` + per-listener filter, `emit` | Partially aligned: prepend/once/bail/waterfall missing (T29, P4-1); parallel/serial are async dispatch - not applicable to the synchronous core, revisit with a reactive/async profile |
| Logger: name hierarchy, levels, diff, exporter extension | `logger(name)` + java.util.logging adapter | Partially aligned: levels and names yes; the exporter extension point is intentionally absent (JVM logging ecosystems - SLF4J/JUL - are the exporters) |
| Registry: enumerate (`get/has/delete/keys/values/entries/forEach`) | no enumeration API | Missing (P4-2: a registry view over typed keys) |
| `Inject` decorator + `ctx.inject` reactive declaration | `ctx.inject` + `@Inject` fields + compile-time processor (D21, T24, T28) | Aligned and beyond (compile-time generation) |
| `plugin(plugin, config)` + `Service.resolveConfig` (intercept-chain config merging) | `plugin(Plugin)` + intercept metadata storage (D17); config consumption unhardened | Partially aligned: the config-resolution semantics are missing (P4-3) |
| `Service` base: name/config/invoke/check/tracker | `Service` marker with start/stop hooks (D9) | Intentional difference: invoke (callable services) and weak config typing are TypeScript idioms; Java services use constructors and typed config objects |
| Fiber runtime, rc6 shadow/caller observation | fiber machine (D7/D19/D20), no shadow observation | Intentional difference: shadow/caller exist for Logger observation in upstream; the JVM logger is simplified - record and revisit if observation is wanted |
| `reflect` service (string-named provision behind a Proxy) | absent | Intentional difference: superseded by typed keys |

### 2.2 @cordisjs/loader

| Upstream | Cordis4j | Status |
|---|---|---|
| `Loader` with `EntryTree` config: `entry` / `group` / `isolate` / `tree` composition, transactional reconcile | `Loader`/`LoaderConfig`/`ComponentEntry`: id-keyed diff, transactional reconcile, reverse-order disposal (D18, T21) | Partially aligned: the reconcile engine yes; the compositional config DSL (group/isolate/tree/include) is missing (P4-2) |
| YAML `include` directives (`@cordisjs/include`) | absent | Missing (P4-2) |

### 2.3 @cordisjs/hmr

| Upstream | Cordis4j | Status |
|---|---|---|
| Algorithms 8/9/10: file-granular module classification, stale detection, transactional reload | `cordis4j-hmr`: jar-granular classification, loader close-and-collect, transactional reload over the core Loader (T26) | Aligned in the JVM form; file-granular import graphs are a JS module-system feature - recorded as the ModuleLayer/file-granular stage 2 in docs/design/hmr-evaluation.md |

### 2.4 @cordisjs/timer

| Upstream | Cordis4j | Status |
|---|---|---|
| `TimerService`: `setTimeout`/`setInterval` (tracked, reverted on dispose), `timeout`/`interval` promise forms | absent | Missing (P4-1/T30: cordis4j-timer module, reversible timers over the core's spawn/task model) |

### 2.5 @cordisjs/logger-console

Console exporters for the upstream Logger. Cordis4j's Logger adapts java.util.logging, so console
output comes from the JVM's logging configuration (JUL handlers, SLF4J bridges) - the JVM
equivalent of exporters. Intentional difference; nothing to port.

### 2.6 @cordisjs/group, @cordisjs/utils, @cordisjs/create

- group: a registry view grouping services - folded into P4-2 (registry views).
- utils: upstream internals - not applicable.
- create: a project scaffold - ecosystem tooling, not library capability; deferred.

## 3. The JVM advantages already exercised (keep these)

1. Typed service keys with compile-time checks (`ServiceKey`, T28 processor) instead of string keys
   and a Proxy.
2. JPMS modules per concern; the core stays zero-dependency.
3. Virtual-thread asynchrony with the guard protocol (D15) instead of the event loop.
4. Provable bytecode retraction: close-and-collect with weak-observed class loaders (T26).
5. Transactional reconcile in the core Loader (T21) and ecosystem bridges (Spring, LangChain4j).

## 4. Plan (P4)

- **P4-1 (event modes, T29)**: `once`, prepend priority, `bail` (short-circuit), and `waterfall`
  (folded return) on the synchronous event bus - the synchronous subset of upstream's dispatch
  modes. Contract v2.2, decision D22.
- **P4-2 (timer module, T30)**: `cordis4j-timer` with reversible `setTimeout`/`setInterval`
  (tracked effects, cancelled on domain unload) and their promise forms, mirroring
  `@cordisjs/timer` on the spawn/task model.
- **P4-3 (config resolution)**: the consumption semantics of intercept metadata - a
  chain-resolved config for services, the Java form of `Service.resolveConfig`.
- **P4-4 (loader DSL + registry views)**: group/isolate/tree composition, `include` directives,
  and a typed registry enumeration - the compositional half of `@cordisjs/loader` and
  `@cordisjs/group`.
