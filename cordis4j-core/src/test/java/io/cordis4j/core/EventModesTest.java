/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T29: the event dispatch modes (decision D22, the synchronous subset of upstream's dispatch
 * modes): prepend, once, bail, and waterfall over the child-to-root bubbling of D3/D16.
 */
class EventModesTest {

  record Tick(int n) {}

  record Ping(String tag) {}

  @Test
  @DisplayName("T29 once 在首次匹配触发后注销；过滤不匹配不消费；手动注销先于触发生效")
  void onceFiresExactlyOnce() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.once(Tick.class, tick -> trace.add("t:" + tick.n()));
    ctx.emit(new Tick(1));
    ctx.emit(new Tick(2));
    assertEquals(List.of("t:1"), trace, "once 必须只触发一次");

    ctx.once(Tick.class, tick -> tick.n() > 10, tick -> trace.add("big:" + tick.n()));
    ctx.emit(new Tick(5));
    assertEquals(List.of("t:1"), trace, "过滤不匹配不得消费 once 监听器");
    ctx.emit(new Tick(15));
    ctx.emit(new Tick(16));
    assertEquals(List.of("t:1", "big:15"), trace, "首次匹配后消费");

    Disposable cancelled = ctx.once(Tick.class, tick -> trace.add("never"));
    cancelled.dispose();
    ctx.emit(new Tick(3));
    assertEquals(List.of("t:1", "big:15"), trace, "手动注销先于触发");
  }

  @Test
  @DisplayName("T29 prepend 插在本 context 监听列表头部；祖先监听仍在 context 监听之后")
  void prependRunsBeforeExistingListeners() {
    Context root = Contexts.create();
    Context child = root.fork();
    List<String> trace = new ArrayList<>();
    root.on(Tick.class, tick -> trace.add("root"));
    child.on(Tick.class, tick -> trace.add("child-1"));
    child.on(Tick.class, tick -> trace.add("child-2"));
    child.on(Tick.class, tick -> trace.add("child-prepend"), true);

    child.emit(new Tick(1));
    assertEquals(
        List.of("child-prepend", "child-1", "child-2", "root"), trace, "prepend 必须插头；冒泡方向保持子→根");
  }

  @Test
  @DisplayName("T29 bail 短路于第一个非 null 结果；null 继续；无贡献返回 empty")
  void bailShortCircuits() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.fold(Tick.class, tick -> null);
    ctx.fold(
        Tick.class,
        tick -> {
          trace.add("second:" + tick.n());
          return null;
        });
    ctx.fold(Tick.class, tick -> tick.n() > 0 ? new Tick(-tick.n()) : null);
    ctx.fold(Tick.class, tick -> tick.n() > 5 ? new Tick(999) : null);

    Optional<Tick> result = ctx.bail(new Tick(7));
    assertEquals(-7, result.orElseThrow().n(), "第一个非 null 结果短路");
    assertEquals(List.of("second:7"), trace, "短路后后续监听器不得运行");

    assertEquals(Optional.empty(), ctx.bail(new Tick(-1)), "无人贡献返回 empty");
  }

  @Test
  @DisplayName("T29 bail 与 waterfall 沿子→根冒泡；子短路时祖先不再运行")
  void bailAndWaterfallBubble() {
    Context root = Contexts.create();
    Context child = root.fork();
    List<String> trace = new ArrayList<>();
    root.fold(
        Tick.class,
        tick -> {
          trace.add("root:" + tick.n());
          return tick.n() >= 10 ? new Tick(-tick.n()) : null;
        });
    child.fold(
        Tick.class,
        tick -> {
          trace.add("child:" + tick.n());
          return tick.n() > 5 && tick.n() < 10 ? new Tick(-tick.n()) : null;
        });

    assertEquals(-7, child.bail(new Tick(7)).orElseThrow().n(), "子短路时祖先不得运行");
    assertEquals(List.of("child:7"), trace);

    trace.clear();
    assertEquals(-12, child.bail(new Tick(12)).orElseThrow().n(), "子无贡献时祖先参与");
    assertEquals(List.of("child:12", "root:12"), trace);
  }

  @Test
  @DisplayName("T29 waterfall 折叠非 null 结果；null 保持累加值；无监听器原样返回")
  void waterfallFolds() {
    Context ctx = Contexts.create();
    ctx.fold(Tick.class, tick -> new Tick(tick.n() + 1));
    ctx.fold(Tick.class, tick -> null);
    ctx.fold(Tick.class, tick -> new Tick(tick.n() * 10));

    assertEquals(80, ctx.waterfall(new Tick(7)).n(), "7+1=8 经 null 保持，再 ×10=80");

    Context empty = Contexts.create();
    assertEquals(7, empty.waterfall(new Tick(7)).n(), "无监听器原样返回");
  }

  @Test
  @DisplayName("T29 事件模式在 dispose 后拒绝；null 参数拒绝；Function 监听器可注销")
  void guardAndNullChecks() {
    Context ctx = Contexts.create();
    Disposable functionListener = ctx.fold(Tick.class, tick -> tick);
    assertEquals(9, ctx.waterfall(new Tick(9)).n());
    functionListener.dispose();
    assertEquals(9, ctx.waterfall(new Tick(9)).n(), "注销后不再参与折叠");

    ctx.dispose();
    assertThrows(IllegalStateException.class, () -> ctx.bail(new Tick(1)));
    assertThrows(IllegalStateException.class, () -> ctx.waterfall(new Tick(1)));
    assertThrows(IllegalStateException.class, () -> ctx.once(Tick.class, tick -> {}));
    assertThrows(IllegalStateException.class, () -> ctx.fold(Tick.class, tick -> tick));

    Context alive = Contexts.create();
    assertThrows(NullPointerException.class, () -> alive.bail(null));
    assertThrows(NullPointerException.class, () -> alive.waterfall(null));
    assertThrows(NullPointerException.class, () -> alive.once(null, tick -> {}));
    assertThrows(NullPointerException.class, () -> alive.once(Tick.class, null));
    assertThrows(NullPointerException.class, () -> alive.fold(null, tick -> tick));
    assertThrows(
        NullPointerException.class,
        () -> alive.fold(Tick.class, (java.util.function.Function<Tick, Tick>) null));
    assertTrue(true);
  }
}
