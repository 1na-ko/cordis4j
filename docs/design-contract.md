# Cordis4j Design Contract

> Status: **v2.0, frozen** (for v0.2.0). Any semantic change must append a new decision-log entry
> (Section 2) and bump this version.
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

Bytecode-level hot module replacement (Section 5.2.2; custom ClassLoader / ModuleLayer
evaluation following the OSGi and pf4j precedents), annotation/proxy-based injection, and
ecosystem integrations (Spring, Quarkus, LangChain4j).

---

## 2. Decision log

| # | Decision | Content | Rationale |
|---|---|---|---|
| D1 | Service access | Explicit ctx.get(ServiceKey) / find returning Optional; annotation injection in P2 | Paper Section 6.4: annotations + compile-time generation are the sanctioned replacement for proxy mediation; P1 stays zero-dependency |
| D2 | Plugin shape | @FunctionalInterface Plugin.apply(Context) -> Disposable; registrations during apply belong to an implicit effect scope | Mirrors the paper's fiber.apply; Java idiom |
| D3 | Event model | Fully synchronous dispatch; emit runs the current context first, then walks the parent chain (child-to-root); a throwing listener propagates and the remaining listeners are skipped (documented) | Matches upstream; virtual-thread asynchrony in P2 |
| D4 | Naming | groupId/package io.cordis4j; artifactId cordis4j-core; JPMS module io.cordis4j.core | Verified conflict-free; frozen |
| D5 | Service keys | ServiceKey<T> = (Class<T> type, String qualifier); the qualifier is a one-dimensional projection of the realm; get(Foo.class) is the default-qualifier sugar | **The most important correction from the feasibility review**: reserves the extension point for paper Section 6.2 multi-provider services and loader realms, avoiding P2 rework |
| D6 | Exception taxonomy | CordisException (base) -> NoSuchServiceException (with key and lookup path) / InactiveAccessException (thrown by P2 declaration checks) / DisposeException (aggregates cleanup failures) | Aligns with the two access failures of upstream Algorithm 6; T7 fixes the aggregation semantics |
| D7 | Lifecycle | Two states, fully synchronous; internal Lifecycle seam (SimpleLifecycle implementation), replaced by the inertial state machine in P2 | Paper Section 4.3.3 needs asynchronous task handles; revisited with virtual threads in P2 |
| D8 | Threading | P1 is single-threaded; the contract states "not synchronized" | Correct first, concurrent later |
| D9 | Service.start/stop | Marked explicitly as an **extension** (not paper semantics): start() on provide within an active plugin domain; stop() in reverse provisioning order on domain reversion | Paper-grounded ordering is covered by T6 instead |
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
            // @Experimental: P1 stores/queries per-key interception metadata (the data-structure
            // part of Section 5.1.2 @@intercept); consumption semantics harden in P2
        <T> Optional<Object> interceptOf(ServiceKey<T> key)
            // queries interception metadata walking up the tree; first hit wins; empty if none

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

    public class SupplyConflictException extends CordisException   // D12
    public class CyclicDependencyException extends CordisException // cycle guard (Progress)
    public class DivertedException extends CordisException         // the guard signal (D15)
    public interface InterceptMetadata { InterceptMetadata merge(InterceptMetadata nearer); } // D17

    public class CordisException extends RuntimeException          // base type
    public class NoSuchServiceException extends CordisException    // carries ServiceKey + lookup path
    public class InactiveAccessException extends CordisException   // type only in P1; thrown by P2 declaration checks
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
- Stability anchors across P2/P3: the ServiceKey shape, the Disposable/EffectScope contracts, the
  exception taxonomy, and the fork-cascade semantics.
- P2 entry points (already reserved): declarative inject plus the Algorithm 3/5 drain ordering,
  the inertial Lifecycle implementation, annotation injection, event filters, virtual-thread
  asynchrony.
- P3 entry points: bytecode-level HMR (custom ClassLoader / ModuleLayer evaluation, following the
  OSGi and pf4j precedents).

---

## 9. References

- Paper: A Programming Paradigm for Spatiotemporal Composability, https://github.com/cordiverse/paper
- Upstream: https://github.com/cordiverse/cordis ; @deepseek-ai/cordis@4.0.1 (vendored in deepseek-harness)
- Feasibility review (archived): docs/design/cordis4j-feasibility-review.md
- Koishi's reversible-plugin design: https://koishi.chat/zh-CN/cookbook/design/disposable.html
