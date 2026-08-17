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
| `Context.baseUrl` (config file directory) | `baseUrl()` + `withBaseUrl(path)` derivation (D25, T33) | Aligned in the immutable-derivation idiom |
| Events: `on` (prepend/priority options), `once`, dispatch modes `emit` / `bail` / `waterfall` / `parallel` / `serial` | synchronous `on` (+ prepend), `once`, `emit`, `bail`, `waterfall` (D22, T29) | Aligned for the synchronous subset; parallel/serial are async dispatch - not applicable to the synchronous core, revisit with a reactive/async profile |
| Logger: name hierarchy, levels, diff, exporter extension | `logger(name)` + java.util.logging adapter | Partially aligned: levels and names yes; the exporter extension point is intentionally absent (JVM logging ecosystems - SLF4J/JUL - are the exporters) |
| Registry: enumerate (`get/has/delete/keys/values/entries/forEach`) | `services()` snapshot of the context's own bindings, typed keys (D24, T32) | Aligned in the typed form; a resolved whole-tree view stays out of scope until the loader composition DSL needs it |
| `Inject` decorator + `ctx.inject` reactive declaration | `ctx.inject` + `@Inject` fields + compile-time processor (D21, T24, T28) | Aligned and beyond (compile-time generation) |
| `plugin(plugin, config)` + `Service.resolveConfig` (intercept-chain config merging) | `plugin(Plugin)` + intercept storage (D17) + `intercepts(key)` chain collection (D23, T31) - callers merge with any policy | Aligned in the Java form: chain collection is the consumption semantics; per-service typed config objects stay the JVM idiom (no weak `config` field to port) |
| `Service` base: name/config/invoke/check/tracker | `Service` marker with start/stop hooks (D9) | Intentional difference: invoke (callable services) and weak config typing are TypeScript idioms; Java services use constructors and typed config objects |
| Fiber runtime, rc6 shadow/caller observation | fiber machine (D7/D19/D20), no shadow observation | Intentional difference: shadow/caller exist for Logger observation in upstream; the JVM logger is simplified - record and revisit if observation is wanted |
| `reflect` service (string-named provision behind a Proxy) | absent | Intentional difference: superseded by typed keys |

### 2.2 @cordisjs/loader

| Upstream | Cordis4j | Status |
|---|---|---|
| `Loader` with `EntryTree` config: `entry` / `group` / `isolate` / `tree` composition, transactional reconcile | `Loader` reconcile engine (D18, T21) + `reconcileTree` over `ComponentSpec` (Group prefixes, Isolate realms, Include inlining) (D26, T33) | Aligned in the typed form; per-node isolation realms instead of upstream's realm table |
| cordis configuration format: `cordis.yml`/`.json` entry trees, patch layers (`plugin-include`), dsh bundle/profile manifests | `cordis4j-loader` (D28, T42-T45): faithful reading (delayed `!!js` -> `JsExpr`, unknown fields preserved, ids generated at read time), upstream patch semantics, both manifests, and the mapping onto `ComponentSpec` with per-entry metadata | Aligned at the format layer; component resolution, JS evaluation, npm packages, and config write-back are host policy by decision D28. Patch override replaces map fields wholesale and missing insert targets warn-and-skip, matching upstream include exactly (0.4.1) |
| YAML `include` directives (`@cordisjs/include`) | `ComponentSpec.Include` inlines another source against the base directory through a caller-supplied resolver - no file format imposed | Aligned in the typed form (no YAML dependency) |

### 2.3 @cordisjs/hmr

| Upstream | Cordis4j | Status |
|---|---|---|
| Algorithms 8/9/10: file-granular module classification, stale detection, transactional reload | `cordis4j-hmr`: jar-granular classification, loader close-and-collect, transactional reload over the core Loader (T26) | Aligned in the JVM form; file-granular import graphs are a JS module-system feature - recorded as the ModuleLayer/file-granular stage 2 in docs/design/hmr-evaluation.md |

### 2.4 @cordisjs/timer

| Upstream | Cordis4j | Status |
|---|---|---|
| `TimerService`: `setTimeout`/`setInterval` (tracked, reverted on dispose), `timeout`/`interval` promise forms | `cordis4j-timer`: `Timers.setTimeout`/`setInterval` (spawned tasks, reverted on domain unload), `Timers.timeout` future form (T30) | Aligned |

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

- ~~P4-1 (event modes, T29)~~ landed: `once`, prepend, `bail`, `waterfall` (contract v2.2, D22).
- ~~P4-2 (timer module, T30)~~ landed: `cordis4j-timer`.
- ~~P4-3 (config resolution)~~ landed: `Context.intercepts(key)` collects the intercept chain
  root-first (contract v2.3, D23, T31); merging policy stays with the caller.
- ~~P4-4 (loader DSL)~~ landed: group/isolate/tree composition, include inlining, and baseUrl
  derivation (D25/D26, T33); the registry view landed separately (D24, T32).
- ~~P4-5 (cordis configuration format)~~ landed: `cordis4j-loader` reads entry trees, patch
  layers, and the dsh manifests, and maps them onto the composition (contract v2.8, D28,
  T42-T45) - aligned at the format layer.
