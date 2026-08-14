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

/**
 * T15: failure routing (paper Section 4.3.4): a failed activation reverts its own effects, does not
 * propagate to siblings or the trigger, and never retries.
 */
class FailureRoutingTest {

  record Engine() {}

  record Adapter() {}

  @Test
  @DisplayName("T15 inject 激活失败不向触发者传播；部分效应回滚；fiber 永不重试")
  void failureRoutedToUnload() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.inject(
        Engine.class,
        (c, engine) -> {
          trace.add("attempt");
          c.provide(new Adapter()); // must be reverted by the failure routing
          throw new IllegalStateException("boom");
        });

    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(new Engine());
              return Disposables.none();
            }); // must NOT throw
    assertEquals(List.of("attempt"), trace);
    assertEquals(true, ctx.find(Adapter.class).isEmpty(), "失败 fiber 的部分效应必须回滚");

    provider.dispose();
    ctx.plugin(
        c -> {
          c.provide(new Engine());
          return Disposables.none();
        });
    assertEquals(List.of("attempt"), trace, "失败的 fiber 不得重试");
  }

  @Test
  @DisplayName("T15 失败 fiber 的兄弟组件不受影响")
  void siblingsUnaffected() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.inject(
        Engine.class,
        (c, engine) -> {
          throw new IllegalStateException("boom");
        });
    ctx.inject(
        Engine.class,
        (c, engine) -> {
          trace.add("sibling-ok");
          return Disposables.none();
        });
    ctx.plugin(
        c -> {
          c.provide(new Engine());
          return Disposables.none();
        });
    assertEquals(List.of("sibling-ok"), trace);
  }

  @Test
  @DisplayName("T15 plugin（显式装载）失败按契约 §6.7 传播并回滚")
  void pluginFailurePropagates() {
    Context ctx = Contexts.create();
    ctx.plugin(
        c -> {
          c.provide(new Engine());
          return Disposables.none();
        });
    assertThrows(
        IllegalStateException.class,
        () ->
            ctx.plugin(
                c -> {
                  c.provide(new Adapter());
                  throw new IllegalStateException("boom");
                }));
    assertEquals(true, ctx.find(Adapter.class).isEmpty());
  }
}
