/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.Service;
import io.cordis4j.core.ServiceKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-context coeffect tables (paper, Section 5.1.2): the value store, the realm overrides, and the
 * interception metadata table. Resolution walks the context chain toward the root.
 */
final class ServiceRegistry {

  private final ContextImpl owner;
  private final Map<ServiceKey<?>, Object> store = new HashMap<>();
  private final Map<Class<?>, String> realmOverrides = new HashMap<>();
  private final Map<ServiceKey<?>, Object> intercepts = new HashMap<>();

  ServiceRegistry(ContextImpl owner) {
    this.owner = owner;
  }

  /**
   * The effective realm for a type: the nearest isolation override walking up from this context, or
   * null when none exists (the default realm).
   */
  private String effectiveRealm(Class<?> type) {
    for (ContextImpl context = owner; context != null; context = context.parent) {
      String realm = context.registry.realmOverrides.get(type);
      if (realm != null) {
        return realm;
      }
    }
    return null;
  }

  /** Installs a realm override on this context only; used by {@code Context.isolate}. */
  void overrideRealm(Class<?> type, String realm) {
    realmOverrides.put(type, Objects.requireNonNull(realm, "realm"));
  }

  <T> Disposable provide(ServiceKey<T> key, T service) {
    Objects.requireNonNull(service, "service");
    String realm = effectiveRealm(key.type());
    ServiceKey<T> storeKey = ServiceKey.of(key.type(), realm != null ? realm : key.qualifier());
    Object previous = store.put(storeKey, service);
    if (previous instanceof Service previousService) {
      previousService.stop(); // overwrite semantics, contract Section 6.4
    }
    if (service instanceof Service started) {
      started.start();
    }
    return Disposables.of(
        () -> {
          if (store.get(storeKey) == service) { // identity check: no-op once overwritten
            store.remove(storeKey);
            if (service instanceof Service stopped) {
              stopped.stop();
            }
          }
        });
  }

  @SuppressWarnings("unchecked")
  <T> T get(ServiceKey<T> key) {
    for (ContextImpl context = owner; context != null; context = context.parent) {
      String realm = context.registry.effectiveRealm(key.type());
      ServiceKey<T> lookup = ServiceKey.of(key.type(), realm != null ? realm : key.qualifier());
      Object value = context.registry.store.get(lookup);
      if (value != null) {
        return (T) value;
      }
    }
    return null;
  }

  <T> Disposable intercept(ServiceKey<T> key, Object metadata) {
    Objects.requireNonNull(metadata, "metadata");
    intercepts.put(key, metadata);
    return Disposables.of(
        () -> {
          if (intercepts.get(key) == metadata) { // identity check: no-op once overwritten
            intercepts.remove(key);
          }
        });
  }

  Object findIntercept(ServiceKey<?> key) {
    for (ContextImpl context = owner; context != null; context = context.parent) {
      Object metadata = context.registry.intercepts.get(key);
      if (metadata != null) {
        return metadata;
      }
    }
    return null;
  }
}
