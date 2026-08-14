/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Per-context listener table. {@link #emit} dispatches to this context first, then walks the
 * ancestor chain up to the root (decision D3: synchronous, child-to-root bubbling).
 */
final class EventBus {

  private final EventBus parent;
  private final Map<Class<?>, List<Consumer<?>>> listeners = new HashMap<>();

  EventBus(EventBus parent) {
    this.parent = parent;
  }

  <E> Disposable on(Class<E> type, Consumer<E> listener) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(listener, "listener");
    listeners.computeIfAbsent(type, key -> new ArrayList<>()).add(listener);
    return Disposables.of(
        () -> {
          List<Consumer<?>> list = listeners.get(type);
          if (list != null) {
            list.remove(listener);
          }
        });
  }

  <E> void emit(E event) {
    EventBus bus = this;
    Class<?> type = event.getClass();
    while (bus != null) {
      List<Consumer<?>> list = bus.listeners.get(type);
      if (list != null) {
        for (Consumer<?> consumer : List.copyOf(list)) {
          @SuppressWarnings("unchecked")
          Consumer<Object> typed = (Consumer<Object>) consumer;
          typed.accept(event);
        }
      }
      bus = bus.parent;
    }
  }
}
