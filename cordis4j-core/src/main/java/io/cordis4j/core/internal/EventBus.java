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
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Per-context listener list. {@code emit} dispatches to this context first, then to each ancestor
 * up to the root (decision D3: synchronous, child-to-root bubbling).
 *
 * <p>A listener registered for a supertype receives events of every subtype; an optional filter
 * runs before the listener. Within one context, listeners run in strict registration order
 * regardless of their registered types, except for prepended listeners (decision D22), which run
 * before the context's other listeners.
 *
 * <p>A second, function-shaped listener list powers the bail and waterfall dispatch modes (decision
 * D22): functions fold event values; a null result means "no contribution".
 */
final class EventBus {

  /** One consumer-shaped registration: the listened type, the filter, and the listener. */
  private static class Registration {
    final Class<?> type;
    final Predicate<Object> filter;
    final Consumer<Object> listener;

    Registration(Class<?> type, Predicate<Object> filter, Consumer<Object> listener) {
      this.type = type;
      this.filter = filter;
      this.listener = listener;
    }
  }

  /** A registration that unregisters itself on its first firing. */
  private static final class OneShot extends Registration {
    OneShot(Class<?> type, Predicate<Object> filter, Consumer<Object> listener) {
      super(type, filter, listener);
    }
  }

  /** One function-shaped registration for the bail and waterfall modes. */
  private record FunctionRegistration(
      Class<?> type, Predicate<Object> filter, Function<Object, Object> listener) {}

  private final EventBus parent;
  private final List<Registration> listeners = new ArrayList<>();
  private final List<FunctionRegistration> functions = new ArrayList<>();

  EventBus(EventBus parent) {
    this.parent = parent;
  }

  synchronized <E> Disposable on(
      Class<E> type, Predicate<E> filter, Consumer<E> listener, boolean prepend) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(filter, "filter");
    Objects.requireNonNull(listener, "listener");
    @SuppressWarnings("unchecked")
    Predicate<Object> unchecked = event -> filter.test((E) event);
    @SuppressWarnings("unchecked")
    Consumer<Object> typed = (Consumer<Object>) listener;
    Registration registration = new Registration(type, unchecked, typed);
    if (prepend) {
      listeners.add(0, registration);
    } else {
      listeners.add(registration);
    }
    return Disposables.of(() -> listeners.remove(registration));
  }

  synchronized <E> Disposable once(Class<E> type, Predicate<E> filter, Consumer<E> listener) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(filter, "filter");
    Objects.requireNonNull(listener, "listener");
    @SuppressWarnings("unchecked")
    Predicate<Object> unchecked = event -> filter.test((E) event);
    @SuppressWarnings("unchecked")
    Consumer<Object> typed = (Consumer<Object>) listener;
    OneShot oneShot = new OneShot(type, unchecked, typed);
    listeners.add(oneShot);
    return Disposables.of(() -> listeners.remove(oneShot));
  }

  synchronized <E> Disposable fold(Class<E> type, Function<E, E> listener) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(listener, "listener");
    @SuppressWarnings("unchecked")
    Function<Object, Object> typed = (Function<Object, Object>) listener;
    FunctionRegistration registration = new FunctionRegistration(type, event -> true, typed);
    functions.add(registration);
    return Disposables.of(() -> functions.remove(registration));
  }

  synchronized <E> void emit(E event) {
    for (Registration registration : List.copyOf(listeners)) {
      if (registration.type.isInstance(event) && registration.filter.test(event)) {
        if (registration instanceof OneShot) {
          listeners.remove(registration); // a once listener is consumed by its firing
        }
        registration.listener.accept(event);
      }
    }
    if (parent != null) {
      parent.emit(event);
    }
  }

  /**
   * Runs the function listeners (this context first, then ancestors) and returns the first non-null
   * result; a result short-circuits the remaining listeners and ancestors.
   */
  synchronized <E> Optional<E> bail(E event) {
    for (FunctionRegistration registration : List.copyOf(functions)) {
      if (registration.type().isInstance(event) && registration.filter().test(event)) {
        @SuppressWarnings("unchecked")
        E result = (E) registration.listener().apply(event);
        if (result != null) {
          return Optional.of(result);
        }
      }
    }
    if (parent != null) {
      return parent.bail(event);
    }
    return Optional.empty();
  }

  /**
   * Folds the function listeners (this context first, then ancestors): a non-null result becomes
   * the next input; the final value is returned (the event itself when nobody contributed).
   */
  synchronized <E> E waterfall(E event) {
    E accumulated = event;
    for (FunctionRegistration registration : List.copyOf(functions)) {
      if (registration.type().isInstance(accumulated) && registration.filter().test(accumulated)) {
        @SuppressWarnings("unchecked")
        E result = (E) registration.listener().apply(accumulated);
        if (result != null) {
          accumulated = result;
        }
      }
    }
    if (parent != null) {
      return parent.waterfall(accumulated);
    }
    return accumulated;
  }
}
