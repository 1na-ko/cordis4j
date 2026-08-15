/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.InterceptMetadata;
import io.cordis4j.core.Service;
import io.cordis4j.core.ServiceKey;
import io.cordis4j.core.SupplyConflictException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-context coeffect tables (paper, Section 5.1.2): the value store, the realm overrides, and the
 * interception metadata table. Resolution walks the context chain toward the root.
 *
 * <p>Every binding carries a unique token and its supplying fiber. The token makes a removal
 * disposable a no-op once the binding was overwritten (even by the same service instance); the
 * owner implements paper Section 4.2's supply uniqueness (two distinct active fibers may not supply
 * one store key). A removal first drains the binding's active dependents (Theorem 63) and only then
 * leaves the store, so dependent teardowns still resolve the dependency.
 *
 * <p>Concurrency: each table is guarded by its registry's monitor; chain lookups lock
 * child-before-parent, one direction only. User code (service hooks, fiber callbacks through {@code
 * FiberRegistry}) runs outside this monitor.
 */
final class ServiceRegistry {

  /** One store entry: the bound service, the registration token, and the supplying fiber. */
  private record Binding(Object service, Object token, Fiber owner) {}

  private final ContextImpl owner;
  private final Map<ServiceKey<?>, Binding> store = new HashMap<>();
  private final Map<Class<?>, String> realmOverrides = new HashMap<>();
  private final Map<ServiceKey<?>, Object> intercepts = new HashMap<>();
  private final Map<ServiceKey<?>, Object> interceptTokens = new HashMap<>();

  ServiceRegistry(ContextImpl owner) {
    this.owner = owner;
  }

  /**
   * The effective realm for a type: the nearest isolation override walking up from this context, or
   * null when none exists (the default realm).
   */
  private String effectiveRealm(Class<?> type) {
    for (ContextImpl context = owner; context != null; context = context.parent) {
      String realm;
      synchronized (context.registry) {
        realm = context.registry.realmOverrides.get(type);
      }
      if (realm != null) {
        return realm;
      }
    }
    return null;
  }

  /** Installs a realm override on this context only; used by {@code Context.isolate}. */
  synchronized void overrideRealm(Class<?> type, String realm) {
    realmOverrides.put(type, Objects.requireNonNull(realm, "realm"));
  }

  /**
   * Snapshots the bindings this context provides, keyed by their effective store key (the realm
   * override already applied) - the registry view of the upstream parity baseline.
   */
  synchronized Map<ServiceKey<?>, Object> snapshot() {
    Map<ServiceKey<?>, Object> snapshot = new java.util.LinkedHashMap<>();
    for (Map.Entry<ServiceKey<?>, Binding> entry : store.entrySet()) {
      snapshot.put(entry.getKey(), entry.getValue().service());
    }
    return snapshot;
  }

  <T> Disposable provide(ServiceKey<T> key, T service) {
    Objects.requireNonNull(service, "service");
    T checked = key.type().cast(service); // fail fast on a key/service type mismatch
    Fiber supplier = Domains.fiber();
    String realm = effectiveRealm(key.type());
    ServiceKey<T> storeKey = ServiceKey.of(key.type(), realm != null ? realm : key.qualifier());
    Object token = new Object();
    Binding existing = put(storeKey, checked, supplier, token);
    if (supplier != null) {
      root().fibers.supplied(supplier, key);
    }
    if (existing != null && existing.service() instanceof Service previousService) {
      previousService.stop(); // overwrite semantics, contract Section 6.4
    }
    if (checked instanceof Service started) {
      started.start();
    }
    root().fibers.notifyBound(storeKey); // Algorithm 3: activating classification
    return Disposables.of(
        () -> {
          if (!isBound(storeKey, token)) {
            return; // no-op once overwritten
          }
          root().fibers.withdraw(storeKey); // Theorem 63: drain dependents before leaving
          removeIfBound(storeKey, token);
          if (supplier != null) {
            root().fibers.unsupplied(supplier, key);
          }
          if (checked instanceof Service stopped) {
            stopped.stop();
          }
        });
  }

  synchronized <T> T get(ServiceKey<T> key) {
    for (ContextImpl context = owner; context != null; context = context.parent) {
      String realm;
      synchronized (context.registry) {
        realm = context.registry.realmOverrides.get(key.type());
      }
      ServiceKey<T> lookup = ServiceKey.of(key.type(), realm != null ? realm : key.qualifier());
      Binding binding = context.registry.store.get(lookup);
      if (binding != null) {
        return lookup.type().cast(binding.service());
      }
    }
    return null;
  }

  <T> Disposable intercept(ServiceKey<T> key, Object metadata) {
    Objects.requireNonNull(metadata, "metadata");
    Object token = new Object();
    synchronized (this) {
      intercepts.put(key, metadata);
      interceptTokens.put(key, token);
    }
    return Disposables.of(
        () -> {
          synchronized (ServiceRegistry.this) {
            if (interceptTokens.get(key) == token) {
              intercepts.remove(key);
              interceptTokens.remove(key);
            }
          }
        });
  }

  /**
   * Resolves interception metadata for a key along the chain (paper Section 5.1.2): all bindings
   * merge from the root toward the owner when every one of them is an {@link InterceptMetadata};
   * otherwise the nearest binding wins.
   */
  /**
   * Collects the interception metadata bound along the tree for a key, from the root to the owner
   * context: the consumption form of upstream's resolveConfig (decision D23) - callers merge the
   * list with any policy, for example the nearer-wins monoid of {@link #findIntercept}.
   */
  List<Object> findIntercepts(ServiceKey<?> key) {
    List<Object> chain = new ArrayList<>();
    for (ContextImpl context = owner; context != null; context = context.parent) {
      Object metadata;
      synchronized (context.registry) {
        metadata = context.registry.intercepts.get(key);
      }
      if (metadata != null) {
        chain.add(metadata);
      }
    }
    Collections.reverse(chain); // root first: nearer bindings last
    return chain;
  }

  Object findIntercept(ServiceKey<?> key) {
    List<Object> chain = findIntercepts(key);
    if (chain.isEmpty()) {
      return null;
    }
    Object merged = chain.get(0);
    for (int i = 1; i < chain.size(); i++) {
      Object nearer = chain.get(i);
      if (merged instanceof InterceptMetadata outer && nearer instanceof InterceptMetadata inner) {
        merged = outer.merge(inner);
      } else {
        return nearer; // mixed metadata kinds keep nearest-wins semantics
      }
    }
    return merged;
  }

  private synchronized Binding put(
      ServiceKey<?> key, Object service, Fiber supplier, Object token) {
    Binding existing = store.get(key);
    if (existing != null
        && existing.owner() != null
        && supplier != null
        && existing.owner() != supplier) {
      throw new SupplyConflictException(
          key, "fiber #" + existing.owner().uid + " and fiber #" + supplier.uid);
    }
    store.put(key, new Binding(service, token, supplier));
    return existing;
  }

  private synchronized boolean isBound(ServiceKey<?> key, Object token) {
    Binding binding = store.get(key);
    return binding != null && binding.token() == token;
  }

  private synchronized void removeIfBound(ServiceKey<?> key, Object token) {
    Binding binding = store.get(key);
    if (binding != null && binding.token() == token) {
      store.remove(key);
    }
  }

  private ContextImpl root() {
    return owner.root;
  }
}
