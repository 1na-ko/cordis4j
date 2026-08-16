/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T52: cycle semantics as implemented (R2) - mutually cyclic declarations are never satisfied, so
 * both fibers simply stay INACTIVE and silent (nothing throws; a thrown "cycle rejection" has no
 * reachable path for mutual cycles), and a self-cycle - a body providing the very key its fiber
 * depends on - does not fail either: the notifyBound selection skips the still-LOADING fiber, the
 * body lands normally, and no CyclicDependencyException escapes to any public API caller.
 */
class CyclicDeclarationsTest {

  static class Left {}

  static class Right {}

  @Test
  @DisplayName("T52 互相环声明：双方静默 INACTIVE，无异常、无激活、handle 可正常退役")
  void mutualCycleStaysSilentlyInactive() {
    Context ctx = Contexts.create();
    AtomicInteger bodies = new AtomicInteger();

    Disposable left =
        ctx.inject(
            Set.of(ServiceKey.of(Right.class)),
            c -> {
              bodies.incrementAndGet();
              c.provide(new Left());
              return Disposables.none();
            });
    Disposable right =
        ctx.inject(
            Set.of(ServiceKey.of(Left.class)),
            c -> {
              bodies.incrementAndGet();
              c.provide(new Right());
              return Disposables.none();
            });

    assertEquals(0, bodies.get(), "互相环的声明永不满足：双方必须保持 INACTIVE，不抛任何异常");
    assertTrue(ctx.find(Left.class).isEmpty(), "环内不得有半激活的绑定上线");
    assertTrue(ctx.find(Right.class).isEmpty(), "环内不得有半激活的绑定上线");

    left.dispose();
    right.dispose();
    assertEquals(0, bodies.get(), "退役后同样不得触发任何激活");
  }

  @Test
  @DisplayName("T52 自环（body 提供自己声明的键）：不重入、body 正常落地、无环异常外泄")
  void selfCycleLandsNormallyWithoutReentry() {
    Context ctx = Contexts.create();
    AtomicInteger completed = new AtomicInteger();
    ServiceKey<Left> key = ServiceKey.of(Left.class);

    // The fiber declares the same key its own body provides. The in-body provide classifies the
    // dependent set; its own fiber is still LOADING there, so the selection skips it - no
    // synchronous re-entry, no CyclicDependencyException on any public API path.
    Disposable declaration =
        ctx.inject(
            key,
            (c, left) -> {
              c.provide(key, new Left());
              completed.incrementAndGet();
              return Disposables.none();
            });

    Disposable bootstrap = ctx.provide(key, new Left());
    assertEquals(1, completed.get(), "自环 body 必须正常落地（LOADING 中的 fiber 不被重入选中）");
    assertTrue(ctx.find(key).isPresent(), "绑定必须仍然可解析");

    bootstrap.dispose();
    declaration.dispose();
    assertEquals(1, completed.get(), "退役后不得触发重激活");
  }
}
