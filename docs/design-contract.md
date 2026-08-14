# Cordis4j Design Contract

> Status: **v1.0, frozen** (for v0.1.0). Any semantic change must append a new decision-log entry
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

Ship the kernel of spatiotemporal composability on the JVM (v0.1.0 vertical slice):

- **Temporal dimension (revertible effects)**: every context mutation carries an explicit
  inverse; the runtime accumulates them in LIFO order and recovers them wholesale on unload
  (paper Section 3.1, Algorithm 1);
- **Spatial dimension (reactive coeffects)**: services are provided and resolved under typed
  keys, with realm qualifiers and isolation derivation (paper Section 3.2, Section 5.1.2);
- **Unified context tree**: `fork()` derives isolated child contexts, `dispose()` cascades
  recovery (paper Section 3.3.1);
- **Component lifecycle**: two states (INACTIVE/ACTIVE), fully synchronous (a simplification of
  paper Section 4.2; the inertial state machine is P2).

### 1.2 Out of scope this round (P2/P3)

Declarative loader and configuration reconciliation (Section 5.2.1), HMR (Section 5.2.2), the
inertial asynchronous state machine (Section 4.3.3), annotation/proxy-based injection (the
upstream mixin), event filters, thread safety, Spring/Quarkus/LangChain4j integrations, and
bytecode-level hot replacement (JVM ClassLoader approaches).

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
   ServiceKey(Class, qualifier). The qualifier plays the realm role; the P2 loader's multi-realm
   support (Section 5.2.1) extends ServiceKey rather than replacing the key scheme.
2. Lookup failure: upstream ctx.get(key) (a store lookup) never fails - what fails is proxy
   property access (INACTIVE_ACCESS / UNDECLARED_ACCESS); Cordis4j get() throws
   NoSuchServiceException, find() returns Optional, and InactiveAccessException is reserved for
   P2 declaration checks.
3. Lifecycle: upstream is the inertial asynchronous state machine (RELOADING/UNLOADING/FAILED);
   P1 is the two-state synchronous SimpleLifecycle; the Lifecycle seam keeps P2 replaceable.
4. Asynchrony: upstream effects and transitions are asynchronous (create_task); P1 is fully
   synchronous; P2 revisits with virtual threads.
5. Service.start/stop: upstream services are values and lifecycle lives at the fiber level;
   Cordis4j's Service hooks are an explicit extension (D9), not part of the paper semantics.
6. Property access: upstream ctx[key] is Proxy-mediated and enforces declarations (Algorithm 6);
   Cordis4j has no dynamic properties; P2 provides the equivalent mediation via @Inject
   annotations plus compile-time/proxy generation.
7. Event filters (ctx.filter): P2.
8. Logger/logger(name): a minimal, zero-dependency alignment with the upstream built-in logger
   service (java.util.logging adapter).

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
12. Single-threaded: all operations are not thread-safe; sharing a context across threads is
    undefined behavior (D8).

---

## 7. Lifecycle model (P1)

- States: INACTIVE / ACTIVE (two states).
- Transitions: domain created -> apply runs (the synchronous form of LOADING) -> ACTIVE;
  dispose -> LIFO reversion -> INACTIVE.
- Seam: internal Lifecycle { void dispose(); }, with SimpleLifecycle as the sole P1
  implementation; P2 replaces it with the inertial state machine (paper Algorithm 5
  refresh/reload/unload and fiber.inertia).

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
