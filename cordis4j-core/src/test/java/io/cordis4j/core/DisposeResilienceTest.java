/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T7: disposal resilience (paper, Section 4.3.4): one failure never stops the rest. */
class DisposeResilienceTest {

  @Test
  @DisplayName("T7 某清理抛异常：其余清理仍执行，异常聚合为 DisposeException")
  void scopeDisposalAggregatesFailures() {
    Context ctx = Contexts.create();
    List<String> order = new ArrayList<>();
    DisposeException error =
        assertThrows(
            DisposeException.class,
            () -> {
              try (var fx = ctx.effect()) {
                fx.track(Disposables.of(() -> order.add("first")));
                fx.track(
                    Disposables.of(
                        () -> {
                          throw new IllegalStateException("boom");
                        }));
                fx.track(Disposables.of(() -> order.add("last")));
              }
            });
    assertEquals(1, error.getSuppressed().length, "失败必须作为 suppressed 聚合上报");
    assertInstanceOf(IllegalStateException.class, error.getSuppressed()[0]);
    assertEquals(List.of("last", "first"), order, "其余清理必须仍然执行（LIFO）");
  }

  @Test
  @DisplayName("T7 上下文 dispose 同样聚合失败且继续其余撤销")
  void contextDisposalAggregatesFailures() {
    Context ctx = Contexts.create();
    List<String> order = new ArrayList<>();
    ctx.plugin(
        pluginCtx -> {
          pluginCtx.provide(new ThrowingService(order));
          return Disposables.none();
        });
    ctx.plugin(
        pluginCtx -> {
          pluginCtx.on(Ping.class, ping -> {});
          return Disposables.of(() -> order.add("cleanup"));
        });

    DisposeException error = assertThrows(DisposeException.class, ctx::dispose);
    assertEquals(1, error.getSuppressed().length);
    assertEquals(List.of("cleanup", "throwing:stop"), order, "其余域必须完成撤销");
  }

  private static final class ThrowingService implements Service {
    private final List<String> order;

    private ThrowingService(List<String> order) {
      this.order = order;
    }

    @Override
    public void stop() {
      order.add("throwing:stop");
      throw new IllegalStateException("stop failed");
    }
  }

  private record Ping() {}
}
