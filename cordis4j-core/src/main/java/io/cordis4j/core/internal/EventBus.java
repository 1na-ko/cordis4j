/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Per-context listener list. {@code emit} dispatches to this context first, then to each ancestor
 * up to the root (decision D3: synchronous, child-to-root bubbling).
 *
 * <p>A listener registered for a supertype receives events of every subtype; an optional filter
 * runs before the listener. Within one context, listeners run in strict registration order
 * regardless of their registered types.
 */
final class EventBus {

  /** One registration: the listened type, the filter, and the listener. */
  private record Registration(Class<?> type, Predicate<Object> filter, Consumer<Object> listener) {}

  private final EventBus parent;
  private final List<Registration> listeners = new ArrayList<>();

  EventBus(EventBus parent) {
    this.parent = parent;
  }

  synchronized <E> Disposable on(Class<E> type, Predicate<E> filter, Consumer<E> listener) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(filter, "filter");
    Objects.requireNonNull(listener, "listener");
    @SuppressWarnings("unchecked")
    Predicate<Object> unchecked = event -> filter.test((E) event);
    @SuppressWarnings("unchecked")
    Consumer<Object> typed = (Consumer<Object>) listener;
    Registration registration = new Registration(type, unchecked, typed);
    listeners.add(registration);
    return Disposables.of(() -> listeners.remove(registration));
  }

  synchronized <E> void emit(E event) {
    for (Registration registration : List.copyOf(listeners)) {
      if (registration.type().isInstance(event) && registration.filter().test(event)) {
        registration.listener().accept(event);
      }
    }
    if (parent != null) {
      parent.emit(event);
    }
  }
}
