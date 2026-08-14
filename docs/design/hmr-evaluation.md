# Bytecode-Level HMR on the JVM: pf4j / OSGi / ModuleLayer Evaluation

> Canonical language: **English**. Chinese translation: docs/zh/hmr-evaluation.zh-CN.md.
> Status: evaluation (roadmap P3 item c, first half: document before code). This document records
> the comparison and the recommended path; it is a proposal to the maintainer, not a decision log
> entry. Implementing the recommendation lands in a separate module and keeps cordis4j-core
> zero-dependency.

## 1. What the paper requires (anchor)

Paper Section 5.2.2 lifts the revertible-effect pattern to the module level: when code changes,
the system replaces the affected components in place, with no restart and no developer-annotated
acceptance boundaries. Its engine has three phases:

- **Algorithm 8, module classification**: from a stashed set (changed files) and an externals set
  (modules that force a full restart), a fixed point classifies every module accepted or declined.
- **Algorithm 9, stale-entry detection**: a component entry is stale exactly when its dependency
  tree intersects the accepted set.
- **Algorithm 10, transactional module reload**: invalidate the accepted modules' caches with a
  backup, then for every stale entry dispose its fiber and instantiate a fresh one from the
  re-imported module; any failure restores the caches and rebuilds every stale entry from its
  backup.

Section 6.4 states the JVM-specific precondition: temporal composability needs the code itself to
be introducible and retractable at runtime, which in a managed runtime means a module registry
whose entries can be evicted and garbage-collected once unreferenced. The JVM ships no such
registry, so the mechanism below must provide it: loading plugin code is an effect whose inverse
is the class loader becoming unreachable and its classes being collectable.

Cordis4j has already realized Algorithm 10 at the **configuration level** (`Loader` /
`ComponentEntry`, decision D18, T21): id-keyed diff, instance identity as the version, and
transactional reconcile with rollback. What remains is the **bytecode level**: turning a changed
class file (or jar) into a re-imported `Plugin` whose new fiber replaces the old one, with the old
classes provably unloadable. The paper's file-level import graph has no direct JVM equivalent, so
the JVM form takes the jar (or class directory) as the module vertex; the dependency graph reduces
to the loader boundary, and the externals set is explicit configuration.

## 2. The candidates

### 2.1 Custom ClassLoader (pure JDK)

A per-plugin class loader (a `URLClassLoader` whose parent is the cordis4j-core loader, or a
custom child-first variant) loads the plugin's classes; `Plugin` (and all core types) come from
the parent, so both sides share the same type identity.

- Introduce: instantiate the loader and load the plugin class.
- Retract: drop every strong reference (loader, classes, instances, threads) and let the GC
  collect; unloading is observable via a `PhantomReference`/`ReferenceQueue` on the loader.
- Isolation: flat classpath semantics; same-named classes collide across plugins unless a
  child-first policy is chosen deliberately.
- Cost/risk: smallest - no dependency, full control, but "classes are collectable" is a discipline
  (no leaked thread, timer, or static reference), not a guarantee the runtime enforces.

### 2.2 ModuleLayer (JDK 9+)

Each plugin lives in its own `ModuleLayer` (`defineModulesWithOneLoader`), forming a real module
graph per plugin.

- Introduce: define the layer from a modular jar (or an automatic module plus its manifest).
- Retract: drop the layer; its loader and classes become collectable when unreachable. Unload
  observability needs the same reference discipline as 2.1, plus layers keeping parent-layer
  references via their class loaders must not be pinned externally.
- Isolation: the strongest - module encapsulation, per-plugin versions of the same module name,
  no accidental visibility.
- Cost/risk: highest among the JDK-only options - plugins must be modular jars (or automatic
  modules with a correct manifest), resolution failures abort loading, reflection across modules
  needs `opens`, and mixing with classpath code is fiddly.

### 2.3 OSGi (Core Release 9, Equinox/Felix)

A complete module runtime: bundle lifecycle, `Import-Package` version resolution, service
registry, and the most mature class-space management on the JVM (a stopped bundle's class space
is discarded).

- Introduce/retract: first-class bundle start/stop; uninstall + refresh evicts the class space.
- Isolation: excellent, with package-level granularity and version ranges.
- Cost/risk: a heavyweight framework with its own programming model (Activator/DS, its own service
  registry). Its lifecycle and service layers duplicate what cordis4j's fibers and coeffects
  already express - adopting OSGi would mean either running two composition models side by side or
  re-engineering the core around it, both against the paper's "Java re-imagining" stance. License
  (EPL-2.0 / Apache-2.0 implementations) is unproblematic.

### 2.4 pf4j (3.15.0, active, Apache-2.0)

A lightweight plugin framework: per-plugin `PluginClassLoader`, lifecycle (create/start/stop/
delete), extension points, zip layout. Actively maintained (3.15.0, January 2026; a security fix
for zip path traversal landed in 3.14.1).

- Introduce/retract: plugin load/unload via a `PluginManager`; 3.14.0 added setting the class
  loader to null on unload, precisely to help GC.
- Isolation: per-plugin classloader, no module encapsulation.
- Cost/risk: moderate, but its lifecycle and extension model overlap cordis4j's fiber model - the
  integration would have to pick one as the source of truth, and pf4j would bring a dependency
  plus its own conventions into a project whose core is deliberately zero-dependency. Class
  unloading remains a discipline, as in 2.1.

## 3. Comparison

| Dimension | Custom ClassLoader | ModuleLayer | OSGi | pf4j |
|---|---|---|---|---|
| Introduce code at runtime | yes | yes | yes (bundle install/start) | yes |
| Retract + GC eviction | by discipline, observable via PhantomReference | layer unreachable -> collectable | class space discarded (mature) | by discipline, loader nulled on unload |
| Isolation | weak (flat classpath) | strong (module graph) | strongest (package/version) | weak (per-plugin loader) |
| Dependency resolution | manual | module resolution at define time | Import-Package version ranges | minimal |
| Programming model conflict with cordis4j | none (plain `Plugin`) | none (plain `Plugin` behind `opens`) | heavy (Activator/DS + own registry) | moderate (lifecycle + extensions) |
| New runtime dependency | none | none | framework (Equinox/Felix) | org.pf4j:pf4j |
| Effort | low | medium | high | medium |

## 4. Recommendation

Implement HMR as a separate module `cordis4j-hmr` in two stages, both zero new runtime
dependencies:

1. **Stage 1 - custom ClassLoader engine (recommended first)**: `BytecodePluginLoader` (per-plugin
   loader whose parent is the cordis4j-core loader) plus a `PluginClassRegistry` keyed by plugin
   path: load (jar -> `Plugin` instance), unload (drop references, observe collection through a
   phantom reference, fail fast on leak detection in tests), and a `HotReloadableLoader`-style
   bridge that plugs the Algorithm 10 shape already in the core `Loader`: dispose the stale
   fiber, instantiate the reloaded plugin, swap; on failure, roll back from the backup. Behavior
   tests (continuing the T series) must prove: reload swaps instances, the old classes become
   unloadable (phantom reference fires, with a GC settle helper), a failed reload restores the
   previous set, and a leaked plugin thread prevents unload (documented, then fixed by the
   test's own discipline).
2. **Stage 2 - ModuleLayer variant (optional, later)**: the same registry interface over
   `defineModulesWithOneLoader` for modular plugins, chosen only if cross-plugin version
   isolation becomes a real requirement.

OSGi and pf4j are not adopted: OSGi duplicates the fiber/service model at framework scale; pf4j
overlaps the lifecycle that cordis4j already owns. Both are recorded here so the decision does not
need to be re-litigated; pf4j remains a useful precedent for per-plugin loader and unload
discipline (its 3.14.0 loader-nulling change mirrors Stage 1's retract step).

## 5. References

- Paper: A Programming Paradigm for Spatiotemporal Composability, Section 5.2.2,
  https://github.com/cordiverse/paper
- pf4j: https://github.com/pf4j/pf4j (3.15.0, 2026-01-27; path-traversal fix in 3.14.1)
- OSGi Core Release 9: https://osgi.github.io/osgi/core/framework.introduction.html
- ModuleLayer mechanics: https://docs.oracle.com/en/java/javase/21/docs/api/java.lang/java/lang/ModuleLayer.html
