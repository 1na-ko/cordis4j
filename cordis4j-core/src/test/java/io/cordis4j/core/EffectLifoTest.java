/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T1: effect scopes revert in LIFO order and dispose is idempotent (paper, Section 3.1.2). */
class EffectLifoTest {

  @Test
  @DisplayName("T1 effect() 域内注册 a、b、c，dispose 按 c→b→a 逆序撤销")
  void revertsLifo() {
    Context ctx = Contexts.create();
    List<String> order = new ArrayList<>();
    var fx = ctx.effect();
    fx.track(Disposables.of(() -> order.add("a")));
    fx.track(Disposables.of(() -> order.add("b")));
    fx.track(Disposables.of(() -> order.add("c")));
    assertEquals(List.of(), order, "未 dispose 前不得执行任何逆");
    fx.dispose();
    assertEquals(List.of("c", "b", "a"), order, "dispose 必须按 LIFO 逆序撤销");
  }

  @Test
  @DisplayName("T1 重复 dispose 幂等；dispose 后 track 抛 IllegalStateException")
  void disposeIsIdempotentAndRejectsTracking() {
    Context ctx = Contexts.create();
    List<String> order = new ArrayList<>();
    var fx = ctx.effect();
    fx.track(Disposables.of(() -> order.add("once")));
    fx.dispose();
    fx.dispose();
    assertEquals(List.of("once"), order, "第二次 dispose 必须是 no-op");
    assertThrows(IllegalStateException.class, () -> fx.track(Disposables.none()));
  }
}
