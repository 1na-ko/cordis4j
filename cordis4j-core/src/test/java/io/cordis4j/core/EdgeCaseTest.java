/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Boundary semantics of contract Section 6, one test per clause. */
class EdgeCaseTest {

  @Test
  @DisplayName("已 dispose 上下文：get/emit/plugin/fork/provide/effect/isolate 抛 IllegalStateException")
  void disposedContextRejectsOperations() {
    Context ctx = Contexts.create();
    ctx.dispose();
    assertThrows(IllegalStateException.class, () -> ctx.get(Marker.class));
    assertThrows(IllegalStateException.class, () -> ctx.find(Marker.class));
    assertThrows(IllegalStateException.class, () -> ctx.emit(new Ping()));
    assertThrows(IllegalStateException.class, () -> ctx.plugin(pluginCtx -> Disposables.none()));
    assertThrows(IllegalStateException.class, ctx::fork);
    assertThrows(IllegalStateException.class, () -> ctx.provide(new Marker()));
    assertThrows(IllegalStateException.class, ctx::effect);
    assertThrows(IllegalStateException.class, () -> ctx.isolate(Marker.class, "r"));
    ctx.dispose(); // 重复 dispose 幂等
  }

  @Test
  @DisplayName("公共 API 拒绝 null 参数（NPE）")
  void nullArgumentsRejected() {
    Context ctx = Contexts.create();
    assertThrows(NullPointerException.class, () -> ctx.get((Class<Marker>) null));
    assertThrows(NullPointerException.class, () -> ctx.get((ServiceKey<Marker>) null));
    assertThrows(NullPointerException.class, () -> ctx.find((ServiceKey<Marker>) null));
    assertThrows(
        NullPointerException.class, () -> ctx.provide((ServiceKey<Marker>) null, new Marker()));
    assertThrows(NullPointerException.class, () -> ctx.provide((Marker) null));
    assertThrows(NullPointerException.class, () -> ctx.isolate(null, "r"));
    assertThrows(NullPointerException.class, () -> ctx.isolate(Marker.class, null));
    assertThrows(
        NullPointerException.class, () -> ctx.intercept(ServiceKey.of(Marker.class), null));
    assertThrows(NullPointerException.class, () -> ctx.on(null, marker -> {}));
    assertThrows(NullPointerException.class, () -> ctx.on(Marker.class, null));
    assertThrows(NullPointerException.class, () -> ctx.emit(null));
    assertThrows(NullPointerException.class, () -> ctx.plugin((Plugin) null));
    assertThrows(NullPointerException.class, () -> ctx.plugin((Object[]) null));
    assertThrows(NullPointerException.class, () -> ctx.logger(null));
    assertThrows(NullPointerException.class, () -> Disposables.of(null));
    assertThrows(NullPointerException.class, () -> Disposables.composite((Disposable[]) null));
  }

  @Test
  @DisplayName("plugin.apply 抛异常：部分效应先 LIFO 撤销，异常再传播")
  void applyFailureRollsBack() {
    Context ctx = Contexts.create();
    RuntimeException boom = new RuntimeException("boom");
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                ctx.plugin(
                    pluginCtx -> {
                      pluginCtx.provide(new Marker());
                      throw boom;
                    }));
    assertSame(boom, thrown, "原始异常必须原样传播");
    assertThrows(NoSuchServiceException.class, () -> ctx.get(Marker.class), "部分效应必须已撤销");
  }

  @Test
  @DisplayName("emit 中监听器抛异常：异常传播，剩余监听器跳过")
  void listenerFailurePropagatesAndSkipsRest() {
    Context ctx = Contexts.create();
    List<String> seen = new ArrayList<>();
    ctx.on(
        Ping.class,
        ping -> {
          throw new IllegalStateException("listener-boom");
        });
    ctx.on(Ping.class, ping -> seen.add("second"));
    assertThrows(IllegalStateException.class, () -> ctx.emit(new Ping()));
    assertEquals(List.of(), seen, "异常之后不得继续投递");
  }

  @Test
  @DisplayName("intercept 元数据写入、查询与撤销")
  void interceptMetadataRoundtrip() {
    Context ctx = Contexts.create();
    ServiceKey<Marker> key = ServiceKey.of(Marker.class);
    Object metadata = new Object();
    Disposable removal = ctx.intercept(key, metadata);
    assertEquals(Optional.of(metadata), ctx.interceptOf(key));
    removal.dispose();
    assertEquals(Optional.empty(), ctx.interceptOf(key));
  }

  private static final class Marker {}

  private record Ping() {}
}
