/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.Context;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.DisposeException;
import io.cordis4j.core.Logger;
import io.cordis4j.core.NoSuchServiceException;
import io.cordis4j.core.Plugin;
import io.cordis4j.core.ServiceKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The single {@link Context} implementation: the unified context of paper Section 3.3.1. Holds the
 * effect accumulator, delegates coeffects to a {@link ServiceRegistry} and events to an {@link
 * EventBus}, and forms the context tree through {@code parent} links.
 */
public final class ContextImpl implements Context {

  /** Context id source; single-threaded per decision D8. */
  private static int nextId = 1;

  final ContextImpl parent;
  private final ContextImpl root;
  private final int id;
  private final EventBus events;
  final ServiceRegistry registry;
  private final Deque<Disposable> effects = new ArrayDeque<>();
  private EffectScopeImpl activeScope;
  private boolean disposed;

  /** Creates a context; {@code parent} is null only for the root. */
  public ContextImpl(ContextImpl parent) {
    this.parent = parent;
    this.root = parent != null ? parent.root : this;
    this.id = nextId++;
    this.events = new EventBus(parent != null ? parent.events : null);
    this.registry = new ServiceRegistry(this);
  }

  private void checkAlive() {
    if (disposed) {
      throw new IllegalStateException("Context #" + id + " is disposed");
    }
  }

  /** Registers an effect into the active scope, or into this context's accumulator. */
  private void track(Disposable effect) {
    if (activeScope != null) {
      activeScope.track(effect);
    } else {
      effects.push(effect);
    }
  }

  @Override
  public <T> T get(ServiceKey<T> key) {
    checkAlive();
    Objects.requireNonNull(key, "key");
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
  public <T> Optional<Object> interceptOf(ServiceKey<T> key) {
    checkAlive();
    Objects.requireNonNull(key, "key");
    return Optional.ofNullable(registry.findIntercept(key));
  }

  @Override
  public EffectScope effect() {
    checkAlive();
    return new EffectScopeImpl();
  }

  @Override
  public <E> Disposable on(Class<E> type, Consumer<E> listener) {
    checkAlive();
    Disposable registration = events.on(type, listener);
    track(registration);
    return registration;
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
  public Disposable plugin(Plugin plugin) {
    checkAlive();
    Objects.requireNonNull(plugin, "plugin");
    EffectScopeImpl domain = new EffectScopeImpl();
    EffectScopeImpl previous = activeScope;
    activeScope = domain;
    try {
      Disposable extra = plugin.apply(this);
      if (extra != null) {
        domain.track(extra);
      }
    } catch (RuntimeException | Error failure) {
      try {
        domain.dispose();
      } catch (DisposeException reversion) {
        failure.addSuppressed(reversion);
      }
      throw failure;
    } finally {
      activeScope = previous;
    }
    track(domain);
    return domain;
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
  public Logger logger(String name) {
    return Logger.jul(Objects.requireNonNull(name, "name"));
  }

  @Override
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    SimpleLifecycle.INSTANCE.revert(effects);
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
