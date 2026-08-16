# Cordis4j Design Contract

> Status: **v2.10, frozen** (for v0.4.1+). Any semantic change must append a new decision-log entry
> (Section 2) and bump this version. v2.6 carried D25/D26; v2.7 belonged to D27 (HMR class
> isolation, shipped in 0.3.0) and corrected the header lag; v2.8 adds D28 (the cordis
> configuration-format bridge in cordis4j-loader, shipped in 0.4.0) with boundary semantics 35;
> v2.9 is the 0.4.1 semantic-clarification batch: boundary semantics 36-44 and the corrections to
> D5's key-space note, boundaries 29/32/33, and the cycle wording. v2.10 (dig round 1) extends
> boundary 36: the two-argument inject form resolves its injected value under the rewritten key.
> Semantic baseline: the Cordis paper, *A Programming Paradigm for Spatiotemporal Composability*,
> Sections 3-5 (section numbers below refer to that paper); reference implementations:
> [cordiverse/cordis](https://github.com/cordiverse/cordis) and `@deepseek-ai/cordis`@4.0.1 (MIT).
> Cordis4j is a **Java re-imagining** of the paper's semantics (inspired-by, not a line-by-line
> port); every difference from the upstream TypeScript API is declared in Section 5.
> Canonical language: **English**. Chinese translation: docs/zh/design-contract.zh-CN.md.

---

## 1. Goals and scope

### 1.1 Goals

Ship the kernel of spatiotemporal composability on the JVM (v0.2.0, full core-library coverage
of the paper's Sections 3-5):

- **Temporal dimension (revertible effects)**: every context mutation carries an explicit
  inverse; the runtime accumulates them in LIFO order and recovers them wholesale on unload
  (paper Section 3.1, Algorithm 1);
- **Spatial dimension (reactive coeffects)**: typed keys with realm qualifiers and isolation
  derivation (Section 5.1.2), plus reactive dependency declaration - activation on satisfaction,
  withdrawal drain, re-activation (Algorithm 3, Theorem 63);
- **Unified context tree**: `fork()` derives isolated child contexts, `dispose()` cascades
  recovery (Section 3.3.1);
- **Component lifecycle**: the four-state fiber machine with inertia - unload waits for in-flight
  activations to land, dependents drain before providers revert (Section 4.2-4.3, Algorithms
  4/5), including failure routing and cycle guards;
- **Asynchrony**: virtual-thread activations, reversible spawned tasks, and the guard protocol
  (Sections 4.3.2-4.3.3);
- **Declarative loader**: id-keyed configuration diffing with transactional reconcile
  (Section 5.2.1, Algorithm 10, configuration level).

### 1.2 Out of scope (P3)

Bytecode-level hot module replacement has landed as a separate module (cordis4j-hmr, stage 1 of
docs/design/hmr-evaluation.md: a zero-dependency custom ClassLoader engine with jar-granular
module classification and transactional reload), leaving out of scope: the ModuleLayer variant
(stage 2) and file-granular import-graph classification. The Quarkus integration is evaluated
and deferred (docs/design/quarkus-evaluation.md recommends a plain CDI module when a concrete
deployment needs it). Compile-time annotation processing for injection has landed as a separate
module (cordis4j-inject-processor, T28); the LangChain4j tool bridge and the Spring integration
landed as separate modules (cordis4j-langchain4j, cordis4j-spring) and live outside this core
contract.

---

## 2. Decision log

| # | Decision | Content | Rationale |
|---|---|---|---|
| D1 | Service access | Explicit ctx.get(ServiceKey) / find returning Optional; annotation injection in P2 | Paper Section 6.4: annotations + compile-time generation are the sanctioned replacement for proxy mediation; P1 stays zero-dependency |
| D2 | Plugin shape | @FunctionalInterface Plugin.apply(Context) -> Disposable; registrations during apply belong to an implicit effect scope | Mirrors the paper's fiber.apply; Java idiom |
| D3 | Event model | Fully synchronous dispatch; emit runs the current context first, then walks the parent chain (child-to-root); a throwing listener propagates and the remaining listeners are skipped (documented) | Matches upstream; virtual-thread asynchrony in P2 |
| D4 | Naming | groupId/package io.cordis4j; artifactId cordis4j-core; JPMS module io.cordis4j.core | Verified conflict-free; frozen |
| D5 | Service keys | ServiceKey<T> = (Class<T> type, String qualifier); the qualifier is a one-dimensional projection of the realm; get(Foo.class) is the default-qualifier sugar | Reserves the extension point for paper Section 6.2 multi-provider services and loader realms, avoiding rework. Consequently a realm label and a qualifier of the same text are the same store key - an isolated declaration is satisfied by an ambient binding carrying the same qualifier text (boundary 36) |
| D6 | Exception taxonomy | CordisException (base) -> NoSuchServiceException (key + lookup path) / InactiveAccessException (declaration checks, D13) / DisposeException (suppressed aggregation) / SupplyConflictException / CyclicDependencyException / DivertedException | Aligns with the two access failures of upstream Algorithm 6 and the remaining guard signals; T7 fixes the aggregation semantics |
| D7 | Lifecycle | Four-state fiber machine (INACTIVE/LOADING/ACTIVE/UNLOADING); inertia manifests as unload waiting for landing (Section 7) | Paper Sections 4.2/4.3.3 |
| D8 | Threading (refined by D19) | The core started synchronous; the current concurrency model is D19 | Correct first, concurrent later |
| D9 | Service.start/stop | Marked explicitly as an **extension** (not paper semantics): start() on provide within an active plugin domain; stop() in reverse provisioning order on domain reversion | Paper-grounded ordering is covered by T6/T12 |
| D10 | License/attribution | Cordis4j is MIT; README credits the Cordis paper (cordiverse/paper) and the reference implementations cordiverse/cordis and @deepseek-ai/cordis (code repositories are MIT); the paper is cited, its license not asserted | Upstream code is fully MIT, no legal obstacle |
| D11 | Reactive coeffects | ctx.inject(deps, effect) declares a fiber (paper Algorithm 3): it activates when every declared key resolves, unloads reactively when a relied binding is withdrawn, and may re-activate; the callback's returned Disposable joins the fiber domain (reverted first) | Implements the paper's satisfaction/notify/refresh; the effect-function shape mirrors Plugin |
| D12 | Supply uniqueness | Two distinct active fibers may not supply one store key (SupplyConflictException); ambient provisioning overwrites freely (administrator semantics) | Paper Section 4.2 disjoint provide sets, fail-fast in Java |
| D13 | Declaration mediation | While a declarative fiber runs, get/find only resolve its declared keys and its own supplies (InactiveAccessException, paper Algorithm 6); plain plugins are unrestricted | The Java form of upstream proxy-mediated access checks |
| D14 | Failure routing | An inject activation failure reverts the partial domain, is recorded and logged, and never retries; it does not propagate (paper Section 4.3.4). plugin() failures keep propagating (clause 6.7) | Sibling isolation of the paper's failure semantics |
| D15 | Asynchrony | pluginAsync runs the effect function on a virtual thread and waits for it to land (inertia); spawn runs long tasks whose handle interrupts and joins them (starting a task is a revertible effect); currentFiber() exposes the guard (isDiverted/checkDiverted) | Paper Sections 4.3.2-4.3.3 in the Java idiom; guard = retired OR not (LOADING/ACTIVE) OR unsatisfied |
| D16 | Event dispatch | Listeners registered for a supertype receive subtypes (isInstance); optional per-listener filters; strict registration order within one context (updates D3) | Java's class hierarchy replaces upstream string keys |
| D17 | Intercept metadata | Metadata implementing InterceptMetadata merges along the chain root-to-lookup, nearer-wins on conflict (the paper's right-biased monoid); other kinds stay nearest-wins | Consumption semantics of the @@intercept slot |
| D18 | Declarative loader | LoaderConfig/ComponentEntry reconcile by id-keyed diff; the component instance is the version (a changed instance reloads); reconcile is transactional (failure restores the previous entries); dispose unloads in reverse load order | Paper Section 5.2.1 / Algorithm 10 at configuration level; record equality is the Java-native config diff |
| D19 | Threading | Registry state is guarded by internal locks in one acquisition direction (fiber registry, then per-context stores, then scopes); user code runs outside them; reactive notifications triggered by a provide run after that provide's monitor is released | No lock cycles; long activations and teardowns (which may join tasks) never hold the registry monitor |
| D20 | Withdrawal order | Unloading a fiber first withdraws every key it supplies (draining all dependents, including still-LOADING ones - the chained unload of inertia) and only then reverts its effects LIFO; a drain-interrupted dependent still resolves the dependency during its teardown | Paper L-Leave/L-Unload and Theorem 63 exactly |
| D21 | Annotation injection | @Inject(qualifier) fields of an instance, assembled by Injects.injectFields(ctx, instance) into one D11 declaration: activation populates the fields as snapshots, withdrawal/retirement clears them, re-satisfaction refills; assembly fails fast on static/final/primitive fields, and an instance without annotated fields is a no-op | Paper Section 6.4 sanctions annotation-mediated access via runtime reflection where the language has no transparent interception primitive; keeps core zero-dependency (JDK reflection only) |
| D22 | Event modes | on(type, listener, prepend) inserts before the context's existing listeners; once fires exactly once then unregisters (filter honored, manual removal still possible); a second, function-shaped listener list (fold) powers bail - the first non-null result short-circuits the dispatch, ancestors included - and waterfall - non-null results fold into the next input, null keeps the accumulator; emit stays the consumer-list path; bubbling stays child-to-root | Upstream DispatchMode bail/waterfall plus the prepend option and once, in the synchronous core's typed form (the upstream parity baseline, docs/design/upstream-parity.md); parallel/serial stay out of scope as async dispatch |
| D23 | Intercept consumption | Context.intercepts(key) collects the interception metadata bound along the tree root-first, nearest-last (the raw chain, no merging); interceptOf stays the nearer-wins monoid over that chain; callers merge the list with any policy; interception keys are realm-unrewritten by design - metadata addressing, not resolution | The Java form of upstream's Service.resolveConfig - chain collection is the consumption semantics, merging policy stays in the caller (the upstream parity baseline) |
| D24 | Registry view | Context.services() snapshots the bindings this context provides (ancestors excluded), keyed by the effective store key with the realm override applied; the snapshot is immutable; enumeration walks the snapshot | The typed form of upstream's registry values/entries; a resolved whole-tree view stays out of scope until the loader composition DSL (parity P4-4) needs it |
| D25 | Base directory | Context.baseUrl() returns the nearest base directory bound by this context or an ancestor (empty when none); Context.withBaseUrl(path) derives a child carrying it, like fork(); relative configuration paths (include references) resolve against it | Upstream Context.baseUrl in the immutable-derivation idiom (fork/isolate) |
| D26 | Loader composition | Loader.reconcileTree flattens a ComponentSpec tree into per-entry load contexts and reconciles through the D18 engine: Group prefixes its children's ids with groupId+':'; Isolate loads its children into a derived isolate(type, realm) context (a per-node realm, disposed once its entries all unload); Include inlines another configuration source resolved against the base directory through a caller-supplied resolver (no file format imposed); duplicate flattened ids fail fast before any change; failures roll back like D18 | Upstream's entry/group/isolate/tree configuration and the include directive in typed form; the flat reconcile(LoaderConfig) is the single-context special case of the same engine |
| D27 | HMR class isolation | cordis4j-hmr loads each plugin jar into a URLClassLoader parented on the cordis4j-core loader: host classes win over same-named plugin classes (plugins always see the host Plugin type), plugins cannot ship their own versions of host dependencies, cross-plugin same-name classes are distinct copies, and there is no module encapsulation; retraction stays close-and-collect with the GC guarantee of T26/T34 | The stage-1 model of docs/design/hmr-evaluation.md section 5; the child-first (with a cordis4j-core exclusion) and ModuleLayer upgrades are evaluated and reserved in docs/design/hmr-isolation-evaluation.md - code follows only when a real requirement appears |
| D28 | Format adaptation boundary | cordis4j-loader bridges upstream's cordis configuration **format** - the entry-tree shape of `@cordisjs/plugin-loader` and the patch semantics of `plugin-include` - onto the core's D26 composition, and nothing beyond: reading is faithful (`cordis.yml`/`.yaml`/`.json` roots are lists of entry rows; the delayed `!!js` tag parses to an opaque JsExpr the host interpolates through a pluggable ExpressionEvaluator; unknown fields survive verbatim; a missing id is generated at read time - upstream's ensureId, without the write-back); patch layers keep upstream semantics (insert appends to the root or into a located group; overrides locate by id anywhere in the tree; a name mismatch skips the patch; config replaces wholesale; a later patch in a layer sees earlier inserts); the two dsh manifests (`dsh.bundle.patch`, the ordered `dsh.profile.bundles`) parse without any package-manager integration; the mapping wraps an entry's isolation table as nested Isolate realms (`true` -> `'#'+entryId` local, a label -> `'@'+label` shared, the first table service outermost), drops disabled entries from the mount while keeping their metadata, and hands config/inject/intercept to the host through EntryMeta; component and service-name resolution is an interface (ComponentResolver) - no JS engine, no npm/registry client, no config write-back | The format is the stable contract of the cordis ecosystem; the runtime decisions (what a name resolves to, how an expression evaluates) are host policy on the JVM - the module is a format bridge, not a runtime |

---

## 3. API contract

Package io.cordis4j.core; implementation classes live in io.cordis4j.core.internal (not exported
by the module).

    public interface Disposable extends AutoCloseable
        Semantics: the inverse of an effect. dispose() is idempotent; close() delegates to it.
        Contract: every API returning a Disposable may be disposed at any time; disposing reverts
        the registration.

    public final class Disposables
        none() -> Disposable: shared no-op singleton.
        of(Runnable action) -> Disposable: runs action at most once on first dispose().
        composite(Disposable... parts) -> Disposable: disposes parts sequentially in argument
        order; failures are collected and reported as a DisposeException with suppressed causes.

    public record ServiceKey<T>(Class<T> type, String qualifier)
        Semantics: service key = type x qualifier (a realm projection). of(type) equals
        of(type, ""). Invariants: type and qualifier are never null (compact constructor).

    public interface Service (extension, D9)
        default void start() {}
        default void stop() {}
        Semantics: start() runs immediately when provided inside an active plugin domain;
        stop() runs in reverse provisioning order when the domain is reverted. Only effective
        when the provided object explicitly implements this interface.

    @FunctionalInterface public interface Plugin
        Disposable apply(Context ctx);
        Semantics: apply runs inside an implicit effect scope (the paper's fiber.apply); every
        registration made through the context during apply belongs to the plugin and is reverted
        LIFO on unload. The returned Disposable joins the domain (usually Disposables.none()).
        If apply throws, the registrations made so far are reverted first (paper Section 4.3.4),
        then the exception propagates with any reversion failures attached as suppressed.

    public interface Context extends Disposable
        -- Coeffects (Section 5.1.2) --
        <T> T get(ServiceKey<T> key)      // walks the tree (realm-aware); NoSuchServiceException with path
        <T> T get(Class<T> type)          // = get(ServiceKey.of(type))
        <T> Optional<T> find(ServiceKey<T> key)  // optional lookup; never throws
        <T> Optional<T> find(Class<T> type)
        <T> Disposable provide(ServiceKey<T> key, T service)
            // overwrite semantics (like upstream set): returns the removal Disposable; the old
            // Disposable becomes a no-op once overwritten
        <T> Disposable provide(T service) // key = concrete class + default qualifier
        <T> Context isolate(Class<T> type, String realm)
            // derives a child context overriding the realm mapping of that type only (Section
            // 5.1.2 derivation semantics); the returned Context IS the child (Context extends
            // Disposable) - disposing it discards the child (implicit recovery, no explicit
            // inverse); the child is also registered as an effect of the active scope
        <T> Disposable intercept(ServiceKey<T> key, Object metadata)
            // stores/queries per-key interception metadata (the data-structure part of
            // Section 5.1.2 @@intercept); consumption semantics: the merge monoid of D17
        <T> Optional<Object> interceptOf(ServiceKey<T> key)
            // queries interception metadata walking up the tree; first hit wins; empty if none
        <T> List<Object> intercepts(ServiceKey<T> key)
            // the raw chain root-first, nearest-last; callers merge with any policy (D23)
        Map<ServiceKey<?>, Object> services()
            // immutable snapshot of this context's provided bindings (ancestors excluded),
            // keyed by the effective store key (D24)

        -- Space (Section 3.3.1) --
        Optional<Path> baseUrl()          // nearest base directory along the tree, or empty (D25)
        Context withBaseUrl(Path)         // derives a child carrying the base directory

        -- Effects (Section 5.1.1, Algorithm 1) --
        EffectScope effect()
            // opens an effect group. Idiom: try (var fx = ctx.effect()) { fx.track(...); ... }
            // close()/dispose() reverts the tracked effects in LIFO order; failures aggregate
            // into DisposeException (T7)

        -- Events (effects that are registrations; D3) --
        <E> Disposable on(Class<E> type, Consumer<E> listener)
        <E> void emit(E event)
            // synchronous: this context's listeners, then the parent chain to the root (a child
            // emit reaches ancestor listeners; an ancestor emit never reaches children)

        -- Space (Section 3.3.1) --
        Context fork()    // derives a child; the child's disposal is an effect of the active scope
        Context root()    // the root context

        -- Composition entry points (Algorithm 4 in Java form) --
        Disposable plugin(Plugin plugin)
        Disposable plugin(Object... services)  // convenience: a plugin that only provides services
        Logger logger(String name)             // minimal Logger + java.util.logging adapter

    public final class Contexts
        static Context create()  // creates a root context

    // ── Reactive coeffects (D11, paper Algorithm 3) ──
    Disposable inject(Set<ServiceKey<?>> deps, Function<Context, Disposable> onSatisfied)
    <T> Disposable inject(ServiceKey<T> dep, BiFunction<Context, T, Disposable> onSatisfied)
    <T> Disposable inject(Class<T> dep, BiFunction<Context, T, Disposable> onSatisfied)
    <T1,T2> Disposable inject(ServiceKey<T1>, ServiceKey<T2>,
                              TriFunction<Context, T1, T2, Disposable> onSatisfied)
        // activates when satisfied; unloads reactively on withdrawal (drained first);
        // re-activates while neither retired nor failed; activation failures route to unload

    // ── Annotation injection (D21, paper Section 6.4) ──
    @interface Inject                                // FIELD; String qualifier() default ""
    public final class Injects
        static Disposable injectFields(Context ctx, Object instance)
        // scans the class hierarchy's @Inject fields (up to Object) into one declaration;
        // populates them as activation-time snapshots, clears them on withdrawal/retirement,
        // refills on re-satisfaction; fail-fast (IllegalArgumentException) on static/final/
        // primitive fields; no annotated field -> Disposables.none()
        interface FieldTarget                         // the accessor shape of Section 6.4
            ServiceKey<?> key()                      // the field's dependency key
            void set(Object value)                   // writes the binding; null clears
        static Disposable injectFields(Context ctx, List<FieldTarget> targets)
        // one declaration over explicit accessors: the runtime form wraps reflection targets,
        // and the compile-time processor (cordis4j-inject-processor) generates direct assignments

    // ── Asynchrony (D15, paper Sections 4.3.2-4.3.3) ──
    Disposable pluginAsync(AsyncPlugin plugin)  // virtual thread; waits for activation to land
    Disposable spawn(Runnable task)             // reversible task: handle interrupts and joins
    Optional<FiberHandle> currentFiber()        // the guard: isDiverted / checkDiverted

    // ── Events (D16) ──
    <E> Disposable on(Class<E> type, Predicate<E> filter, Consumer<E> listener)

    // ── Declarative loader (D18, paper Section 5.2.1 / Algorithm 10) ──
    record ComponentEntry(String id, Plugin component)   // instance identity is the version
    record LoaderConfig(List<ComponentEntry> entries)    // ids unique
    final class Loader implements Disposable             // id-keyed diff, transactional reconcile

    // ── Loader composition (D26, upstream's entry/group/isolate/tree in typed form) ──
    sealed interface ComponentSpec
        record Entry(String id, Plugin component)        // a plain component
        record Group(String id, List<ComponentSpec>)     // prefixes children with id+':'
        record Isolate(Class<?> type, String realm, List<ComponentSpec>)
            // children load into a derived isolate context; a per-node realm, disposed when
            // its entries all unload
        record Include(Path file, Function<Path, List<ComponentSpec>> resolver)
            // inlines another source resolved against the base directory; the resolver picks
            // the file format
    Loader.reconcileTree(List<ComponentSpec>) / reconcileTree(Path baseUrl, List<ComponentSpec>)
        // flatten, then the D18 engine with per-entry load contexts

    public class SupplyConflictException extends CordisException   // D12
    public class CyclicDependencyException extends CordisException // cycle guard (Progress)
    public class DivertedException extends CordisException         // the guard signal (D15)
    public interface InterceptMetadata { InterceptMetadata merge(InterceptMetadata nearer); } // D17

    public class CordisException extends RuntimeException          // base type
    public class NoSuchServiceException extends CordisException    // carries ServiceKey + lookup path
    public class InactiveAccessException extends CordisException   // carries the declaration checks of Algorithm 6 (D13)
    public class DisposeException extends CordisException          // suppressed = all cleanup failures

---

## 4. Mapping to the paper / upstream

| Cordis4j | Paper construct | Upstream TS |
|---|---|---|
| provide / get | set/get (Algorithm 2: two-layer resolution k -> rho(k) -> sigma) | ctx.provide(name, value) / ctx.get(name) |
| ServiceKey(type, qualifier) | P1 projection of key k and realm symbol rho(k) | string keys + ctx.isolate(name, realm) |
| effect().track(d) | ctx.effect: inverse prepended to the accumulator (LIFO) | ctx.effect(callback) |
| plugin(Plugin) | use/instantiation (Algorithm 4: the parent effect carries the child unload) | ctx.plugin(plugin) |
| fork() | context-tree fork (Section 3.3.1: child sees parent, never the reverse) | ctx.fork() |
| dispose() | withdrawal (Section 4.3.1) + accumulator recovery | fiber dispose |
| isolate(type, realm) | Section 5.1.2 derived child overriding the realm table | ctx.isolate(name, realm) |
| intercept(key, meta) | Section 5.1.2 @@intercept data structure | ctx.intercept(name, config) |
| on / emit | events as effects; tree bubbling | ctx.on / ctx.emit |

---

## 5. Deviations and extensions (explicit differences from upstream TS)

1. Key scheme: upstream uses string keys plus per-type module augmentation; Cordis4j uses
   ServiceKey(Class, qualifier). The qualifier plays the realm role; the loader's multi-realm
   support (Section 5.2.1) extends ServiceKey rather than replacing the key scheme.
2. Lookup failure: upstream ctx.get(key) (a store lookup) never fails - what fails is proxy
   property access (INACTIVE_ACCESS / UNDECLARED_ACCESS); Cordis4j get() throws
   NoSuchServiceException, find() returns Optional, and InactiveAccessException carries the
   declaration checks of Algorithm 6 (D13).
3. Lifecycle: the synchronous core drives a four-state fiber machine (INACTIVE / LOADING /
   ACTIVE / UNLOADING, paper Section 4.2); inertia appears as unload-waits-for-landing, including
   the chained unload of still-LOADING dependents (D20).
4. Asynchrony: upstream effects and transitions are asynchronous (create_task); Cordis4j offers
   both the synchronous core and the virtual-thread forms (pluginAsync / spawn, D15). The effect
   iterator of Algorithm 1 appears as the guard protocol (currentFiber / isDiverted) instead of
   language-level generators.
5. Service.start/stop: upstream services are values and lifecycle lives at the fiber level;
   Cordis4j's Service hooks are an explicit extension (D9), not part of the paper semantics.
6. Property access: upstream ctx[key] is Proxy-mediated; Cordis4j mediates declarative fibers'
   get/find instead (D13); annotation-based injection remains future work.
7. Event filters: provided as per-listener predicates on Context.on(type, filter, listener)
   (D16); upstream's declarative filter registries are not mirrored.
8. Logger/logger(name): a minimal, zero-dependency alignment with the upstream built-in logger
   service (java.util.logging adapter).
9. Reactive re-activation reuses the fiber (fresh effect domain per activation); the paper's
   reload keeps the same fiber identity too, but upstream TS recreates plugin instances - the
   callback must therefore be idempotent-safe to re-run.

---

## 6. Boundary semantics (each clause fixed by tests)

1. dispose is idempotent: repeated dispose is a no-op.
2. dispose re-entrancy: disposing the same scope/context from inside a reversion is a no-op.
3. A disposed context: get/emit/plugin/fork/provide/effect/isolate throw IllegalStateException.
4. provide overwrite: providing the same key again replaces the binding; the old Disposable
   becomes a no-op; a replaced service's stop() runs immediately (extension D9).
5. Null rejection: all public APIs reject null with NullPointerException (requireNonNull).
6. Listener failure: a throwing listener propagates to the emit caller; remaining listeners
   (including ancestors) are not invoked.
7. plugin.apply failure: registered effects are reverted LIFO, then the exception propagates.
8. Lookup path: get walks the tree upward; at each level the realm override for the type is
   consulted - when present and different from the key's qualifier the level is skipped, otherwise
   the level's store is consulted.
9. isolate derivation: the child inherits everything from the parent except the overridden realm
   for the given type; disposing the child discards it wholesale.
10. fork cascade: a child's disposal is registered as an effect of the parent's active scope; on
    parent dispose, the child reverts before the parent's earlier effects (LIFO across the fiber
    tree, T6).
11. Event bubbling direction: child-to-ancestor only; listeners within one context run in
    registration order.
12. Concurrency: registry state is internally locked (D19); user callbacks must not block on
    other threads that need the tree's state (documented; the runtime itself never does so).
13. Reactive lifecycle: an inject fiber activates when satisfied; a withdrawn binding unloads it
    reactively; a re-satisfied declaration re-activates it; retired or failed fibers never
    re-activate (T11, T15).
14. Drain order: unloading a provider withdraws its supplies first; every dependent - including
    one still LOADING - unloads before any of the provider's own effects revert, and each
    dependent's teardown still resolves the withdrawn binding (T12, D20).
15. Supply uniqueness: a second active fiber supplying an occupied store key throws
    SupplyConflictException and its plugin registration rolls back; ambient provides overwrite
    freely (T13).
16. Declaration mediation: get/find inside a declarative fiber resolve only its declared keys
    and its own supplies; events are not mediated (T14).
17. Guard: a spawned task inherits its spawner's fiber; isDiverted is true once the fiber is
    retired, unloading/inactive, or its declaration stopped resolving (T20).
18. pluginAsync waits for the activation to land; checked activation failures propagate wrapped
    in CordisException; a spawned task's handle interrupts and joins it on domain unload (T19).
19. Events: a supertype listener receives subtype events; per-listener filters run before the
    listener (T17).
20. Intercept metadata: all-InterceptMetadata chains merge root-to-lookup (nearer wins on
    conflict); mixed kinds keep nearest-wins (T18).
21. Loader: reconcile loads new ids, unloads vanished ids, reloads changed instances
    (instance identity is the version); a failed reconcile restores the previous set;
    dispose unloads in reverse load order (T21).
22. Repeat provide: providing the same instance twice under one key makes the first removal
    disposable a no-op; the current one removes the binding (T23).
23. Event modes: a once listener fires exactly once and then unregisters (filter and manual
    removal honored); a prepended listener runs before its context's existing listeners, with
    bubbling direction unchanged; bail short-circuits on the first non-null fold result and
    skips the ancestors after it; waterfall folds non-null results (null keeps the accumulator)
    and returns the event unchanged when nobody contributes (T29).
24. Intercept consumption: intercepts(key) collects the metadata bound along the tree
    root-first, nearest-last without merging; interceptOf(key) equals the nearer-wins
    InterceptMetadata monoid over that chain; a chain with mixed kinds keeps the raw values
    (T31).
25. Registry view: services() snapshots this context's provided bindings only (ancestors
    excluded), keyed by the effective store key with the realm override applied; the snapshot
    is immutable; overwrites and removals are reflected by later snapshots (T32).
26. Base directory: baseUrl() walks the tree for the nearest binding; withBaseUrl(path) derives
    a child whose descendants inherit the binding; disposed contexts reject both (T33).
27. Loader composition: groups prefix children ids with ':' separators; isolation realms load
    their children into derived contexts so sibling realms coexist without supply conflicts,
    and a realm is disposed once its entries all unload; includes inline their source resolved
    against the base directory; duplicate flattened ids fail fast before any change; a failing
    component rolls the tree reconcile back to the previous set (T33).
28. Annotation injection: an instance's @Inject fields form one declaration (D21) - populated
    when every field key resolves, cleared when a relied supply withdraws or the declaration
    retires, refilled on re-satisfaction; fields hold activation-time snapshots, so an ambient
    overwrite does not touch an activated declaration, while a supplying fiber's unload drains
    it through the fiber-level supply relation (T24).
29. Dispatch reentrancy: listeners, filters, and fold functions run outside the bus monitor
    (D19); a listener may register, unregister, and re-emit mid-dispatch without deadlocking; a
    blocked listener does not serialize unrelated event operations; a once listener fires exactly
    once under racing dispatches (T38). A once listener whose registration is manually disposed
    after its CAS consumption but before its execution still runs that one execution - industry
    once semantics, where the consumption, not the delivery, is the commitment.
30. Isolate x inject: dependency declarations index, and declaration mediation (D13) compares,
    on the effective (realm-rewritten) store key - the same base provide, notify, and withdraw
    speak - so reactive dependents inside an isolated subtree classify normally; a default-realm
    binding never satisfies an isolated declaration (isolation hides the outer default binding,
    matching upstream) (T37).
31. Registry release: disposing the declaration of a fiber that never ran a full unload -
    reactively drained, failed, or never satisfied - removes the fiber from the registry, so its
    owner subtree becomes collectable (T36).
32. Realm reuse: the loader keys each isolation realm by its isolate-chain path (the sequence
    of nested realms from the root, group prefixes excluded) and reuses its derived context across
    reconciles; groups do not constitute isolation boundaries, a nested inner realm never merges
    with a top-level one carrying the same label, unchanged entries inside the realm do not
    reload, and a realm is disposed only once truly drained after the whole reconcile lands
    (T39/T53).
33. Hook rollback: a Service.start()/stop() failure during a first provide removes the binding
    before the failure propagates, leaving no orphan the caller could not dispose; over a previous
    binding, the failure restores that binding (token and owner intact) and best-effort restarts
    the old service - the key never evaporates and dependents stay on the pre-overwrite stable
    state (T40/T51).
34. Interrupted async activation: a caller interrupted while waiting for pluginAsync loses the
    handle, so it joins the ambient scope - the context's own disposal unloads the fiber whatever
    state it landed in (T41).
35. Format layer (cordis4j-loader, D28): reading preserves the `!!js` tag as an unevaluated
    JsExpr and unknown fields verbatim, and generates a missing id (8 hex digits) at read time;
    an insertion without an id appends to the root, with one it appends into the located group
    (a missing, non-group, or malformed target skips the patch with a warning); an override
    locates its row by id anywhere in the tree - a present-but-mismatched name skips the patch,
    config replaces wholesale, and a missing target warns without failing; a later patch in the
    same layer locates what an earlier one inserted; a package without the `dsh` key declares
    nothing; the mapping turns `true` into the local realm `#<entryId>`, a label into the shared
    realm `@<label>` (the first table service wrapping outermost), drops disabled entries (a
    group's own flag included) from the mount while keeping their metadata, and rejects entries
    without ids (T42-T45).
36. Realm/qualifier key space (D5): a realm label and a qualifier of the same text are one store
    key - an isolated declaration rewrites to that key, an ambient binding carrying the same
    qualifier text satisfies it, withdrawing that binding drains the dependent, and re-providing
    re-activates it (T46). The two-argument (and three-argument) inject forms resolve their
    injected values under the same rewritten key the declaration was indexed on, so a satisfied
    declaration always sees its binding inside the body (T61).
37. Declaration mediation base (D13): the comparison speaks effective keys computed from the
    context the lookup resolves through, not the declaration's owner - a realm-declared fiber
    reading the default key through the root is rejected, and vice versa (T47).
38. Raced retirement: a declaration retired between the notifyBound selection and the activation
    never runs its body - and an interrupted pluginAsync caller racing a context dispose still
    receives the CordisException while the orphan fiber retires and unloads in the handler
    itself (T48/T49).
39. Dispose completion: a context dispose whose ambient phase throws still closes the executor
    behind it (close failures aggregate as suppressed), and a loader realm's accounting lands
    even when a component's teardown throws, so drained realms really discard (T50).
40. Overwrite rollback: see boundary 33 - the previous binding is restored, never evaporated
    (T51).
41. Cycles: mutually cyclic declarations are never satisfied and simply stay INACTIVE, silently,
    like upstream - nothing throws; the synchronous re-entry guard exists only for a self-cycle
    (a body providing the very key its fiber depends on, which the selection itself prevents)
    (T52).
42. Loader realm keys and group inheritance (D28): the realm key is the isolate-chain path with
    group prefixes excluded (boundary 32); a group's isolate and intercept tables propagate down
    the prototype chain, nearer rows overriding the same service name; falsy isolation labels
    (null, false, empty string) mount no realm, and non-string non-boolean labels fail fast
    (T53/T54).
43. Patch tables and targets (D28): an override's intercept/isolate tables replace the target's
    wholesale (an absent table keeps the target's; unlike upstream, a null cannot clear the
    field - Java records cannot express absence), extras keep per-key merging, and broken
    insertion targets skip with a warning (T55).
44. Flattened metadata keys (D28): EntryMeta is keyed by the flattened id (group prefixes
    included), duplicate flattened ids fail fast, and those keys join a reconciled tree by id
    end to end (T56).

---

## 7. Lifecycle model

- Fiber states: INACTIVE / LOADING / ACTIVE / UNLOADING (paper Section 4.2, synchronous form).
- Transitions: activate runs the effect function inside a fresh effect domain (LOADING -> ACTIVE);
  unload withdraws supplies (draining dependents, chained through still-LOADING ones), reverts the
  domain LIFO, and hands the fiber a fresh domain for a possible re-activation; a failure during
  activation routes to unload and freezes the fiber (failed, never retried).
- Inertia: an unload encountering a LOADING fiber waits for the activation to land first
  (paper Section 4.3.3); user code always runs outside the registry monitor (D19).
- Asynchrony: pluginAsync/spawn carry activations and long tasks on virtual threads; task handles
  are revertible effects (interrupt + join).

---

## 8. Evolution strategy

- Semantic versioning: breaking changes are allowed during 0.x, but each must update this
  contract, the decision log, and the CHANGELOG.
- Stability anchors across 0.x: the ServiceKey shape, the Disposable/EffectScope contracts, the
  exception taxonomy, and the fork-cascade semantics.
- Landed in v0.2.0: declarative inject with the Algorithm 3/5 drain ordering, the four-state
  lifecycle with inertia, event filters, virtual-thread asynchrony, and the declarative loader
  (D11-D20).
- Landed since (v2.1): runtime-reflection annotation injection - `@Inject` fields assembled by
  `Injects.injectFields` into one reactive declaration (D21, T24); event dispatch modes - prepend,
  once, bail, waterfall (D22, T29) - closing the synchronous subset of the upstream dispatch modes
  recorded in the parity baseline docs/design/upstream-parity.md; intercept-chain consumption -
  intercepts(key) as the Java form of resolveConfig (D23, T31); the registry view -
  services() as the typed form of upstream's registry enumeration (D24, T32); the loader
  composition DSL - group/isolate/tree/include over the D18 engine, with baseUrl derivation
  (D25, D26, T33).
- Ecosystem (module-level, outside this core contract; no decision-log entry): cordis4j-langchain4j
  exposes `CordisTool` services of a session context as LangChain4j tools that follow the
  reactive-coeffect lifecycle (T25); cordis4j-spring provides a Context bean and @CordisService
  beans that follow bean lifecycles, withdrawing bindings in the stop phase before any bean is
  destroyed so the drain keeps boundary 13/14 (T27, T35).
- Format bridge (module-level, decision D28): cordis4j-loader reads the cordis configuration
  format - entry trees with the delayed `!!js` tag, patch layers, and the dsh manifests - and
  maps it onto the D26 composition; component resolution and expression evaluation stay host
  policy (T42-T45). The module depends on snakeyaml and jackson-databind; the core stays
  zero-dependency.
- HMR (roadmap c, module-level, outside this core contract; no decision-log entry): the evaluation
  (docs/design/hmr-evaluation.md) and the stage-1 engine (cordis4j-hmr) - a zero-dependency
  custom ClassLoader engine with jar-granular module classification, loader close-and-collect
  retraction, and transactional reload over the core Loader (T26).
- Compile-time injection (paper Section 6.4, module-level, outside this core contract; no
  decision-log entry): cordis4j-inject-processor validates @Inject fields at compile time
  (public top-level class, non-static/final/primitive/private fields, named package) and emits a
  zero-reflection injector per class through the {@code Injects.FieldTarget} accessor shape (T28).
- Remaining: the ModuleLayer HMR variant (stage 2) with file-granular import-graph classification,
  and the Quarkus integration (evaluated and deferred, docs/design/quarkus-evaluation.md).

---

## 9. References

- Paper: A Programming Paradigm for Spatiotemporal Composability, https://github.com/cordiverse/paper
- Upstream: https://github.com/cordiverse/cordis ; @deepseek-ai/cordis@4.0.1 (vendored in deepseek-harness)
- Koishi's reversible-plugin design: https://koishi.chat/zh-CN/cookbook/design/disposable.html
