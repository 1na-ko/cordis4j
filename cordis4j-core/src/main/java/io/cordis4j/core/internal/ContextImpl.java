/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.AsyncPlugin;
import io.cordis4j.core.Context;
import io.cordis4j.core.CordisException;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.FiberHandle;
import io.cordis4j.core.InactiveAccessException;
import io.cordis4j.core.Logger;
import io.cordis4j.core.NoSuchServiceException;
import io.cordis4j.core.Plugin;
import io.cordis4j.core.ServiceKey;
import io.cordis4j.core.TriFunction;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The single {@link Context} implementation: the unified context of paper Section 3.3.1. Holds the
 * ambient effect accumulator, delegates coeffects to a {@link ServiceRegistry}, events to an {@link
 * EventBus}, reactive composition to the tree-wide {@link FiberRegistry}, and forms the context
 * tree through {@code parent} links.
 */
public final class ContextImpl implements Context {

  /** Context id source. */
  private static final AtomicInteger nextId = new AtomicInteger(1);

  final ContextImpl parent;
  final ContextImpl root;
  private final int id;
  private final EventBus events;
  final ServiceRegistry registry;
  final FiberRegistry fibers;

  /** The accumulator for registrations made outside any fiber or explicit scope. */
  private final EffectScopeImpl ambient = new EffectScopeImpl();

  private volatile boolean disposed;
  private volatile ExecutorService executor;
  private Path baseUrl;

  /** Creates a context; {@code parent} is null only for the root. */
  public ContextImpl(ContextImpl parent) {
    this.parent = parent;
    this.root = parent != null ? parent.root : this;
    this.id = nextId.getAndIncrement();
    this.events = new EventBus(parent != null ? parent.events : null);
    this.registry = new ServiceRegistry(this);
    this.fibers = parent != null ? parent.fibers : new FiberRegistry(this);
  }

  private void checkAlive() {
    if (disposed) {
      throw new IllegalStateException("Context #" + id + " is disposed");
    }
  }

  /** Registers an effect into the fiber domain executing on this thread, or the ambient scope. */
  private void track(Disposable effect) {
    EffectScopeImpl domain = Domains.domain();
    if (domain != null) {
      domain.track(effect);
    } else {
      ambient.track(effect);
    }
  }

  /** Declaration mediation (paper Algorithm 6): a declarative fiber only sees its own keys. */
  private void checkAccess(ServiceKey<?> key) {
    Fiber current = Domains.fiber();
    if (current != null
        && current.declarative()
        && !current.dependencies.contains(key)
        && !current.providedKeys.contains(key)) {
      throw new InactiveAccessException(key, "undeclared access");
    }
  }

  @Override
  public <T> T get(ServiceKey<T> key) {
    checkAlive();
    Objects.requireNonNull(key, "key");
    checkAccess(key);
    T value = registry.get(key);
    if (value == null) {
      throw new NoSuchServiceException(key, describePath());
    }
    return value;
  }

  @Override
  public <T> T get(Class<T> type) {
    return get(ServiceKey.of(type));
  }

  @Override
  public <T> Optional<T> find(ServiceKey<T> key) {
    checkAlive();
    Objects.requireNonNull(key, "key");
    checkAccess(key);
    return Optional.ofNullable(registry.get(key));
  }

  @Override
  public <T> Optional<T> find(Class<T> type) {
    return find(ServiceKey.of(type));
  }

  @Override
  public <T> Disposable provide(ServiceKey<T> key, T service) {
    checkAlive();
    Objects.requireNonNull(key, "key");
    Disposable removal = registry.provide(key, service);
    track(removal);
    return removal;
  }

  @Override
  public <T> Disposable provide(T service) {
    Objects.requireNonNull(service, "service");
    @SuppressWarnings("unchecked")
    Class<T> type = (Class<T>) service.getClass();
    return provide(ServiceKey.of(type), service);
  }

  @Override
  public <T> Context isolate(Class<T> type, String realm) {
    checkAlive();
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(realm, "realm");
    ContextImpl child = new ContextImpl(this);
    child.registry.overrideRealm(type, realm);
    track(Disposables.of(child::dispose));
    return child;
  }

  @Override
  public <T> Disposable intercept(ServiceKey<T> key, Object metadata) {
    checkAlive();
    Objects.requireNonNull(key, "key");
    Disposable removal = registry.intercept(key, metadata);
    track(removal);
    return removal;
  }

  @Override
  public Map<ServiceKey<?>, Object> services() {
    checkAlive();
    return java.util.Collections.unmodifiableMap(registry.snapshot());
  }

  @Override
  public <T> Optional<Object> interceptOf(ServiceKey<T> key) {
    checkAlive();
    Objects.requireNonNull(key, "key");
    return Optional.ofNullable(registry.findIntercept(key));
  }

  @Override
  public <T> List<Object> intercepts(ServiceKey<T> key) {
    checkAlive();
    Objects.requireNonNull(key, "key");
    return List.copyOf(registry.findIntercepts(key));
  }

  @Override
  public EffectScope effect() {
    checkAlive();
    return new EffectScopeImpl();
  }

  @Override
  public <E> Disposable on(Class<E> type, Consumer<E> listener) {
    return on(type, event -> true, listener);
  }

  @Override
  public <E> Disposable on(Class<E> type, Predicate<E> filter, Consumer<E> listener) {
    checkAlive();
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(filter, "filter");
    Objects.requireNonNull(listener, "listener");
    Disposable registration = events.on(type, filter, listener, false);
    track(registration);
    return registration;
  }

  @Override
  public <E> Disposable on(Class<E> type, Consumer<E> listener, boolean prepend) {
    checkAlive();
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(listener, "listener");
    Disposable registration = events.on(type, event -> true, listener, prepend);
    track(registration);
    return registration;
  }

  @Override
  public <E> Disposable once(Class<E> type, Consumer<E> listener) {
    return once(type, event -> true, listener);
  }

  @Override
  public <E> Disposable once(Class<E> type, Predicate<E> filter, Consumer<E> listener) {
    checkAlive();
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(filter, "filter");
    Objects.requireNonNull(listener, "listener");
    Disposable registration = events.once(type, filter, listener);
    track(registration);
    return registration;
  }

  @Override
  public <E> Disposable fold(Class<E> type, Function<E, E> listener) {
    checkAlive();
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(listener, "listener");
    Disposable registration = events.fold(type, listener);
    track(registration);
    return registration;
  }

  @Override
  public <E> Optional<E> bail(E event) {
    checkAlive();
    Objects.requireNonNull(event, "event");
    return events.bail(event);
  }

  @Override
  public <E> E waterfall(E event) {
    checkAlive();
    Objects.requireNonNull(event, "event");
    return events.waterfall(event);
  }

  @Override
  public <E> void emit(E event) {
    checkAlive();
    Objects.requireNonNull(event, "event");
    events.emit(event);
  }

  @Override
  public Context fork() {
    checkAlive();
    ContextImpl child = new ContextImpl(this);
    track(Disposables.of(child::dispose));
    return child;
  }

  @Override
  public Context root() {
    return root;
  }

  @Override
  public Optional<Path> baseUrl() {
    checkAlive();
    for (ContextImpl context = this; context != null; context = context.parent) {
      Path bound = context.baseUrl;
      if (bound != null) {
        return Optional.of(bound);
      }
    }
    return Optional.empty();
  }

  @Override
  public Context withBaseUrl(Path baseUrl) {
    checkAlive();
    Objects.requireNonNull(baseUrl, "baseUrl");
    ContextImpl child = new ContextImpl(this);
    child.baseUrl = baseUrl;
    track(Disposables.of(child::dispose));
    return child;
  }

  @Override
  public Disposable plugin(Plugin plugin) {
    checkAlive();
    Objects.requireNonNull(plugin, "plugin");
    Fiber fiber = fibers.register(this, Set.of(), plugin::apply, true);
    try {
      fibers.activate(fiber);
    } catch (RuntimeException | Error failure) {
      fibers.unregister(fiber);
      throw failure;
    }
    Disposable handle = fibers.handle(fiber);
    track(handle);
    return handle;
  }

  @Override
  public Disposable pluginAsync(AsyncPlugin plugin) {
    checkAlive();
    Objects.requireNonNull(plugin, "plugin");
    Fiber fiber = fibers.register(this, Set.of(), plugin::apply, true);
    Future<?> activation = executor().submit(() -> fibers.activate(fiber));
    try {
      activation.get(); // wait for the activation to land (inertia of Section 4.3.3)
    } catch (ExecutionException executed) {
      fibers.unregister(fiber);
      Throwable cause = executed.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new CordisException("Async plugin activation failed", cause);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CordisException("Interrupted while activating an async plugin", interrupted);
    }
    Disposable handle = fibers.handle(fiber);
    track(handle);
    return handle;
  }

  @Override
  public Disposable spawn(Runnable task) {
    checkAlive();
    Objects.requireNonNull(task, "task");
    EffectScopeImpl inheritedDomain = Domains.domain();
    Fiber inheritedFiber = Domains.fiber();
    Future<?> future =
        root.executor()
            .submit(
                () -> {
                  EffectScopeImpl previousDomain = Domains.domain();
                  Fiber previousFiber = Domains.fiber();
                  Domains.set(inheritedDomain, inheritedFiber); // the task joins its fiber
                  try {
                    task.run();
                  } finally {
                    Domains.set(previousDomain, previousFiber);
                  }
                });
    Disposable handle =
        Disposables.of(
            () -> {
              future.cancel(true); // interrupt; the task must land (join below)
              try {
                future.get();
              } catch (CancellationException expected) {
                // never started: nothing to land
              } catch (ExecutionException failed) {
                root.logger("io.cordis4j.core.task")
                    .warn("Spawned task failed: {}", failed.getCause());
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
    track(handle); // the enclosing domain stops the task on unload, LIFO
    return handle;
  }

  @Override
  public Optional<FiberHandle> currentFiber() {
    Fiber fiber = Domains.fiber();
    if (fiber == null) {
      return Optional.empty();
    }
    return Optional.of(
        new FiberHandle() {
          @Override
          public boolean isDiverted() {
            return fibers.diverted(fiber);
          }

          @Override
          public void checkDiverted() {
            if (isDiverted()) {
              throw new io.cordis4j.core.DivertedException();
            }
          }
        });
  }

  @Override
  public Disposable plugin(Object... services) {
    Objects.requireNonNull(services, "services");
    return plugin(
        ctx -> {
          for (Object service : services) {
            ctx.provide(service);
          }
          return Disposables.none();
        });
  }

  @Override
  public Disposable inject(
      Set<ServiceKey<?>> dependencies, Function<Context, Disposable> onSatisfied) {
    Objects.requireNonNull(onSatisfied, "onSatisfied");
    return injectInternal(dependencies, ctx -> onSatisfied.apply(ctx));
  }

  @Override
  public <T> Disposable inject(
      ServiceKey<T> dependency, BiFunction<Context, T, Disposable> onSatisfied) {
    Objects.requireNonNull(dependency, "dependency");
    Objects.requireNonNull(onSatisfied, "onSatisfied");
    return injectInternal(Set.of(dependency), ctx -> onSatisfied.apply(ctx, ctx.get(dependency)));
  }

  @Override
  public <T> Disposable inject(
      Class<T> dependency, BiFunction<Context, T, Disposable> onSatisfied) {
    Objects.requireNonNull(dependency, "dependency");
    return inject(ServiceKey.of(dependency), onSatisfied);
  }

  @Override
  public <T1, T2> Disposable inject(
      ServiceKey<T1> first,
      ServiceKey<T2> second,
      TriFunction<Context, T1, T2, Disposable> onSatisfied) {
    Objects.requireNonNull(first, "first");
    Objects.requireNonNull(second, "second");
    Objects.requireNonNull(onSatisfied, "onSatisfied");
    Set<ServiceKey<?>> dependencies = Set.copyOf(new LinkedHashSet<>(Arrays.asList(first, second)));
    return injectInternal(
        dependencies, ctx -> onSatisfied.apply(ctx, ctx.get(first), ctx.get(second)));
  }

  /**
   * Instantiates a declarative fiber: it activates as soon as every dependency resolves, unloads
   * reactively when one is withdrawn, and may re-activate if all dependencies resolve again while
   * it is neither retired nor failed.
   */
  private Disposable injectInternal(Set<ServiceKey<?>> dependencies, FiberBody body) {
    checkAlive();
    Fiber fiber = fibers.register(this, dependencies, body, false);
    Disposable handle = fibers.handle(fiber);
    track(handle);
    if (!fiber.retired && !fiber.failed && fibers.satisfied(fiber)) {
      fibers.activate(fiber); // failure is routed to unload and recorded, not propagated
    }
    return handle;
  }

  @Override
  public Logger logger(String name) {
    return Logger.jul(Objects.requireNonNull(name, "name"));
  }

  @Override
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    ambient.dispose(); // unloads fibers and joins their spawned tasks, LIFO
    ExecutorService service = executor;
    if (service != null) {
      service.close(); // wait for remaining carrier threads to land
    }
  }

  private ExecutorService executor() {
    ExecutorService service = executor;
    if (service == null) {
      synchronized (this) {
        if (executor == null) {
          executor = Executors.newVirtualThreadPerTaskExecutor();
        }
        service = executor;
      }
    }
    return service;
  }

  private String describePath() {
    StringBuilder path = new StringBuilder("looked up through the context chain: ");
    for (ContextImpl context = this; context != null; context = context.parent) {
      if (context != this) {
        path.append(" -> ");
      }
      path.append('#').append(context.id);
      if (context.parent == null) {
        path.append(" (root)");
      }
    }
    return path.toString();
  }
}
