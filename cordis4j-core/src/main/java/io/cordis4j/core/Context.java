/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The unified context of the paradigm (paper, Section 3.3.1): carries the revertible-effect
 * accumulator and the coeffect tables, and forms a tree through {@link #fork()}.
 *
 * <p>Every interaction between a component and its environment passes through the context.
 * Registrations made through this interface ({@link #provide}, {@link #on}, {@link #plugin}, {@link
 * #fork}, {@link #isolate}, {@link #intercept}) are tracked as revertible effects of the enclosing
 * scope and are reverted in LIFO order on {@link #dispose()}.
 *
 * <p>P1 semantics are single-threaded (decision D8): a context must not be shared across threads.
 * All methods reject {@code null} arguments with {@link NullPointerException}, and any method other
 * than {@link #dispose()} throws {@link IllegalStateException} once the context is disposed.
 */
public interface Context extends Disposable {

  // ── Coeffects (paper, Section 5.1.2) ──────────────────────────────────────

  /**
   * Resolves a service binding, walking this context and its ancestors.
   *
   * @param <T> the service type
   * @param key the service key
   * @return the bound value, never null
   * @throws NoSuchServiceException if no binding is found up to the root
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code key} is null
   */
  <T> T get(ServiceKey<T> key);

  /**
   * Resolves a service binding under the default qualifier.
   *
   * @param <T> the service type
   * @param type the service type
   * @return the bound value, never null
   * @throws NoSuchServiceException if no binding is found up to the root
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code type} is null
   */
  <T> T get(Class<T> type);

  /**
   * Resolves a service binding without throwing when absent.
   *
   * @param <T> the service type
   * @param key the service key
   * @return the bound value, or empty when absent
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code key} is null
   */
  <T> Optional<T> find(ServiceKey<T> key);

  /**
   * Resolves a service binding under the default qualifier without throwing when absent.
   *
   * @param <T> the service type
   * @param type the service type
   * @return the bound value, or empty when absent
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code type} is null
   */
  <T> Optional<T> find(Class<T> type);

  /**
   * Provides a service binding in this context (paper Algorithm 2).
   *
   * <p>Providing the same key again overwrites the previous binding: the previous removal
   * disposable becomes a no-op, and the previous service's {@link Service#stop()} runs immediately
   * (extension D9). The returned disposable reverts the registration and is itself a tracked effect
   * of the enclosing scope.
   *
   * @param <T> the service type
   * @param key the service key
   * @param service the value to bind
   * @return a disposable that removes the binding; no-op once overwritten or reverted
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code key} or {@code service} is null
   */
  <T> Disposable provide(ServiceKey<T> key, T service);

  /**
   * Provides a service binding keyed by the service's concrete class and the default qualifier.
   *
   * @param <T> the service type
   * @param service the value to bind
   * @return a disposable that removes the binding
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code service} is null
   */
  <T> Disposable provide(T service);

  /**
   * Derives a child context whose effective realm for {@code type} is {@code realm} (paper, Section
   * 5.1.2).
   *
   * <p>The child inherits everything from this context; only the realm mapping of {@code type} is
   * redirected, so bindings provided in the child for {@code type} resolve in the child to the
   * child's own realm and never disturb this context. Disposing the returned context discards the
   * child and all its bindings. The child is also reverted when the enclosing scope is reverted.
   *
   * @param <T> the service type
   * @param type the service type to isolate
   * @param realm the realm label for the child subtree
   * @return the derived child context, which is itself the disposable that discards it
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code type} or {@code realm} is null
   */
  <T> Context isolate(Class<T> type, String realm);

  /**
   * Binds interception metadata for a key in this context (paper, Section 5.1.2, data-structure
   * part only).
   *
   * <p>Experimental in P1: the metadata is stored and queryable through {@link #interceptOf}, but
   * how providers consume it is hardened in P2.
   *
   * @param <T> the service type
   * @param key the service key
   * @param metadata the metadata to bind
   * @return a disposable that removes the metadata; no-op once overwritten or reverted
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code key} or {@code metadata} is null
   */
  <T> Disposable intercept(ServiceKey<T> key, Object metadata);

  /**
   * Snapshots the service bindings this context provides (decision D24): the registry view of the
   * upstream parity baseline, keyed by the effective store key (the realm override applied). The
   * snapshot is immutable and does not include ancestor bindings.
   *
   * @return an immutable map of this context's provided bindings, never null
   * @throws IllegalStateException if this context is disposed
   */
  Map<ServiceKey<?>, Object> services();

  /**
   * Returns the interception metadata bound nearest to this context for a key.
   *
   * <p>Experimental in P1.
   *
   * @param <T> the service type
   * @param key the service key
   * @return the metadata, or empty when none is bound
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code key} is null
   */
  <T> Optional<Object> interceptOf(ServiceKey<T> key);

  /**
   * Collects the interception metadata bound along the tree for a key, from the root to this
   * context (decision D23): the consumption form of upstream's resolveConfig. Callers merge the
   * list with any policy - the {@link #interceptOf} result is exactly the nearer-wins monoid of
   * {@link InterceptMetadata} over this list.
   *
   * @param <T> the service type
   * @param key the service key
   * @return the bound metadata, root first and nearest last; empty when none is bound
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code key} is null
   */
  <T> List<Object> intercepts(ServiceKey<T> key);

  // ── Effects (paper, Section 5.1.1, Algorithm 1) ───────────────────────────

  /**
   * Opens a self-contained effect scope.
   *
   * <p>The canonical use is try-with-resources:
   *
   * <pre>
   * try (var fx = ctx.effect()) {
   *   fx.track(...);
   * }
   * </pre>
   *
   * Closing the scope reverts its tracked effects in LIFO order; failures are aggregated into a
   * {@link DisposeException}. A scope that is never closed leaves its effects untracked.
   *
   * @return a new effect scope
   * @throws IllegalStateException if this context is disposed
   */
  EffectScope effect();

  /** A group of tracked effects: the synchronous form of the paper's effect accumulator. */
  interface EffectScope extends Disposable {

    /**
     * Registers a disposable so that it is reverted (LIFO) when this scope is disposed.
     *
     * @param <D> the disposable type
     * @param effect the disposable to track
     * @return {@code effect}, for chaining
     * @throws IllegalStateException if this scope is already disposed
     * @throws NullPointerException if {@code effect} is null
     */
    <D extends Disposable> D track(D effect);
  }

  // ── Events (effects that are registrations; decision D3) ─────────────────

  /**
   * Registers a synchronous listener for an event type.
   *
   * <p>The returned disposable reverts the registration and is a tracked effect of the enclosing
   * scope. Event dispatch is synchronous: {@link #emit} invokes listeners of this context first,
   * then those of each ancestor up to the root.
   *
   * @param <E> the event type
   * @param type the event type
   * @param listener the listener
   * @return a disposable that unregisters the listener
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code type} or {@code listener} is null
   */
  <E> Disposable on(Class<E> type, Consumer<E> listener);

  /**
   * Registers a filtered synchronous listener: the listener runs only when {@code filter} accepts
   * the event. Filtering happens before dispatch, per listener.
   *
   * @param <E> the event type
   * @param type the event type
   * @param filter the predicate an emitted event must satisfy, never null
   * @param listener the listener
   * @return a disposable that unregisters the listener
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if any argument is null
   */
  <E> Disposable on(Class<E> type, Predicate<E> filter, Consumer<E> listener);

  /**
   * Registers a synchronous listener that runs before the listeners already registered in this
   * context (decision D22: the prepend option of upstream's dispatch modes). Ancestor listeners
   * still run after this context's own.
   *
   * @param <E> the event type
   * @param type the event type
   * @param listener the listener
   * @param prepend when true, the listener runs before this context's existing listeners
   * @return a disposable that unregisters the listener
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code type} or {@code listener} is null
   */
  <E> Disposable on(Class<E> type, Consumer<E> listener, boolean prepend);

  /**
   * Registers a one-shot synchronous listener (decision D22): it fires on the first matching event
   * and unregisters itself, before or after running.
   *
   * @param <E> the event type
   * @param type the event type
   * @param listener the listener
   * @return a disposable that unregisters the listener before its first firing
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code type} or {@code listener} is null
   */
  <E> Disposable once(Class<E> type, Consumer<E> listener);

  /**
   * Registers a filtered one-shot synchronous listener: it fires on the first matching, accepted
   * event and unregisters itself, before or after running.
   *
   * @param <E> the event type
   * @param type the event type
   * @param filter the predicate an emitted event must satisfy, never null
   * @param listener the listener
   * @return a disposable that unregisters the listener before its first firing
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if any argument is null
   */
  <E> Disposable once(Class<E> type, Predicate<E> filter, Consumer<E> listener);

  /**
   * Registers a function-shaped synchronous listener for the {@link #bail(Object)} and {@link
   * #waterfall(Object)} dispatch modes (decision D22): it receives the event and returns a new
   * value, or null to make no contribution.
   *
   * @param <E> the event type
   * @param type the event type
   * @param listener the listener
   * @return a disposable that unregisters the listener
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code type} or {@code listener} is null
   */
  <E> Disposable fold(Class<E> type, Function<E, E> listener);

  /**
   * Dispatches in bail mode (decision D22, upstream DispatchMode.bail): function listeners of this
   * context run in registration order, then those of each ancestor; the first non-null result
   * short-circuits the dispatch and is returned.
   *
   * @param <E> the event type
   * @param event the event to dispatch
   * @return the first non-null listener result, or empty when nobody contributed
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code event} is null
   */
  <E> Optional<E> bail(E event);

  /**
   * Dispatches in waterfall mode (decision D22, upstream DispatchMode.waterfall): function
   * listeners fold the event value - a non-null result becomes the next listener's input, this
   * context first and then each ancestor - and the final value is returned.
   *
   * @param <E> the event type
   * @param event the event to dispatch
   * @return the folded value; the event itself when no listener contributed
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code event} is null
   */
  <E> E waterfall(E event);

  /**
   * Emits an event synchronously to this context and then to each ancestor up to the root.
   *
   * <p>If a listener throws, the exception propagates to the caller and the remaining listeners are
   * not invoked (decision D3).
   *
   * @param <E> the event type
   * @param event the event to emit
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code event} is null
   */
  <E> void emit(E event);

  // ── Space (paper, Section 3.3.1) ─────────────────────────────────────────

  /**
   * Forks a child context: it resolves this context's services and events, but nothing registered
   * in the child is visible here.
   *
   * <p>The child's disposal is a tracked effect of the enclosing scope, so reverting this context
   * cascades to the child (paper Algorithm 4).
   *
   * @return the child context
   * @throws IllegalStateException if this context is disposed
   */
  Context fork();

  /**
   * Returns the root context of this context tree.
   *
   * @return the root context, never null
   */
  Context root();

  /**
   * Returns the base directory against which relative configuration paths (for example include
   * references) resolve (decision D25, upstream's Context.baseUrl): the nearest binding of this
   * context or an ancestor.
   *
   * @return the base directory, or empty when none was set
   */
  Optional<Path> baseUrl();

  /**
   * Derives a child context carrying a base directory: it inherits everything from this context
   * (like {@link #fork()}) and additionally binds {@code baseUrl} for itself and its descendants.
   *
   * @param baseUrl the base directory to bind
   * @return the derived child context, which is itself the disposable that discards it
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code baseUrl} is null
   */
  Context withBaseUrl(Path baseUrl);

  // ── Composition entry points (paper Algorithm 4) ─────────────────────────

  /**
   * Applies a plugin: opens an implicit effect scope, runs {@link Plugin#apply(Context)}, and
   * tracks the resulting domain as an effect of the enclosing scope.
   *
   * <p>Disposing the returned disposable reverts every registration the plugin made, in LIFO order.
   *
   * @param plugin the plugin to apply
   * @return a disposable that unloads the plugin
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code plugin} is null
   */
  Disposable plugin(Plugin plugin);

  /**
   * Applies a convenience plugin that only provides the given services, each keyed by its concrete
   * class and the default qualifier.
   *
   * @param services the services to provide
   * @return a disposable that unloads the plugin
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code services} or any element is null
   */
  Disposable plugin(Object... services);

  /**
   * Applies a plugin whose effect function may block, on a virtual thread (paper, Section 4.3.3,
   * asynchrony). The call waits for the activation to land (inertia): it returns once {@code apply}
   * completed, or rethrows its failure with any reversion failures attached as suppressed
   * exceptions.
   *
   * <p>Long-lived work started by {@code apply} should run through {@link #spawn} so that unloading
   * the plugin interrupts and joins it - starting a task is an effect whose inverse is stopping it.
   *
   * @param plugin the plugin to apply
   * @return a disposable that unloads the plugin, joining its spawned tasks first
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code plugin} is null
   */
  Disposable pluginAsync(AsyncPlugin plugin);

  /**
   * Runs a long-lived task on the tree's virtual-thread executor and returns its handle as a
   * tracked effect of the enclosing scope: disposing the handle interrupts the task and waits for
   * it to land, and an enclosing plugin domain does so automatically when it unloads.
   *
   * <p>The task may poll {@link #currentFiber()} and check diversion to stop early when its plugin
   * is unloaded. A failing task is reported to the {@code io.cordis4j.core.task} logger; its
   * failure never propagates to sibling tasks.
   *
   * @param task the task to run, never null
   * @return a disposable that interrupts and joins the task
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code task} is null
   */
  Disposable spawn(Runnable task);

  /**
   * Returns the fiber executing on this thread, if any: the guard of the paper's effect iterator
   * (Section 4.3.2). Code inside a plugin or an {@code inject} callback can poll diversion and stop
   * early, letting the runtime revert only the effects accumulated so far.
   *
   * @return the current fiber's handle, or empty outside any fiber
   */
  Optional<FiberHandle> currentFiber();

  // ── Reactive coeffects (paper, Section 3.2.2 and Algorithm 3) ────────────

  /**
   * Declares a reactive dependency (paper Algorithm 3): the fiber activates - running {@code
   * onSatisfied} inside its own effect domain - as soon as every key in {@code dependencies}
   * resolves from this context, and unloads reactively when a binding it relies on is withdrawn,
   * with the drain guarantee of Theorem 63 (its teardown still resolves the dependency).
   *
   * <p>While activated, the fiber's service lookups are checked against its declaration (Algorithm
   * 6): resolving a key outside {@code dependencies} and the keys the fiber itself supplies throws
   * {@link InactiveAccessException}. An activation failure is routed to unload (paper Section
   * 4.3.4): the fiber's partial effects are reverted, the failure is recorded and logged, and the
   * fiber never retries; the failure does not propagate to the caller. Disposing the returned
   * disposable retires the fiber permanently.
   *
   * @param dependencies the keys the fiber requires
   * @param onSatisfied the effect function run on activation, and again on re-activation; its
   *     returned disposable (nullable) joins the fiber's domain as the first-reverted cleanup
   * @return a disposable that retires and unloads the fiber
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code dependencies}, any element, or {@code onSatisfied} is
   *     null
   */
  Disposable inject(Set<ServiceKey<?>> dependencies, Function<Context, Disposable> onSatisfied);

  /**
   * Declares a single-dependency fiber with the resolved value handed to the effect function.
   *
   * @param <T> the dependency type
   * @param dependency the required key
   * @param onSatisfied the effect function; its returned disposable (nullable) joins the domain
   * @return a disposable that retires and unloads the fiber
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code dependency} or {@code onSatisfied} is null
   */
  <T> Disposable inject(ServiceKey<T> dependency, BiFunction<Context, T, Disposable> onSatisfied);

  /**
   * Declares a single-dependency fiber under the default qualifier.
   *
   * @param <T> the dependency type
   * @param dependency the required type
   * @param onSatisfied the effect function; its returned disposable (nullable) joins the domain
   * @return a disposable that retires and unloads the fiber
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if {@code dependency} or {@code onSatisfied} is null
   */
  <T> Disposable inject(Class<T> dependency, BiFunction<Context, T, Disposable> onSatisfied);

  /**
   * Declares a two-dependency fiber with both resolved values handed to the effect function.
   *
   * @param <T1> the first dependency type
   * @param <T2> the second dependency type
   * @param first the first required key
   * @param second the second required key
   * @param onSatisfied the effect function; its returned disposable (nullable) joins the domain
   * @return a disposable that retires and unloads the fiber
   * @throws IllegalStateException if this context is disposed
   * @throws NullPointerException if any argument is null
   */
  <T1, T2> Disposable inject(
      ServiceKey<T1> first,
      ServiceKey<T2> second,
      TriFunction<Context, T1, T2, Disposable> onSatisfied);

  /**
   * Returns a named logger (aligned with the upstream built-in logger service).
   *
   * @param name the logger name
   * @return a logger for {@code name}
   * @throws NullPointerException if {@code name} is null
   */
  Logger logger(String name);
}
