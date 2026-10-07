/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T94-T97: the upstream call wrappers (F4) - throttle's leading edge and window-end trailing with
 * the noTrailing option, debounce's quiet-window reset, and the cancellation discipline (dispose
 * drops the pending execution and turns later triggers into no-ops; unloading the plugin that
 * created the handle reverts it automatically). Semantics pinned against upstream
 * {@code @cordisjs/timer} {@code ctx.throttle}/{@code ctx.debounce} (parity table 2.4).
 */
class ThrottleDebounceTest {

  @Test
  @DisplayName("T94 throttle：首触立即执行，窗口内重触发合并为窗口末一次尾随（最新触发胜出）")
  void throttleLeadsThenTrailsAtTheWindowEnd() throws Exception {
    Context ctx = Contexts.create();
    AtomicInteger count = new AtomicInteger();
    CountDownLatch lead = new CountDownLatch(1);
    CountDownLatch trail = new CountDownLatch(1);
    Timers.Trigger throttled =
        Timers.throttle(
            ctx,
            () -> {
              if (count.incrementAndGet() == 1) {
                lead.countDown();
              } else {
                trail.countDown();
              }
            },
            150);

    throttled.run(); // first trigger: leading edge, synchronous on this thread
    assertTrue(lead.await(2, TimeUnit.SECONDS), "首触必须立即执行");
    assertEquals(1, count.get());

    throttled.run(); // inside the window: coalesced into the trailing at the window end
    throttled.run();
    assertEquals(1, count.get(), "窗口内触发不得立即执行");

    assertTrue(trail.await(3, TimeUnit.SECONDS), "窗口末必须补一次尾随执行");
    assertEquals(2, count.get(), "多次窗口内触发只合并为一次尾随");
    Thread.sleep(400);
    assertEquals(2, count.get(), "窗口末之后不得再执行");
    throttled.dispose();
    ctx.dispose();
  }

  @Test
  @DisplayName("T95 throttle(noTrailing)：尾随被抑制，窗口过后下一触重新走首触")
  void throttleWithNoTrailingSuppressesTheTrailing() throws Exception {
    Context ctx = Contexts.create();
    AtomicInteger count = new AtomicInteger();
    Timers.Trigger throttled = Timers.throttle(ctx, count::incrementAndGet, 150, true);

    throttled.run(); // leading edge
    throttled.run(); // in window: suppressed entirely
    throttled.run();
    assertEquals(1, count.get());
    Thread.sleep(400); // past the window: no trailing may ever fire
    assertEquals(1, count.get(), "noTrailing 不得产生尾随执行");

    throttled.run(); // window elapsed: a fresh leading edge
    assertEquals(2, count.get(), "窗口过后必须重新立即执行");
    throttled.dispose();
    ctx.dispose();
  }

  @Test
  @DisplayName("T96 debounce：静默期满才执行一次，期间每次触发都重置窗口")
  void debounceFiresOnceAfterTheQuietWindow() throws Exception {
    Context ctx = Contexts.create();
    AtomicInteger count = new AtomicInteger();
    CountDownLatch fired = new CountDownLatch(1);
    Timers.Trigger debounced =
        Timers.debounce(
            ctx,
            () -> {
              count.incrementAndGet();
              fired.countDown();
            },
            200);

    debounced.run();
    debounced.run();
    debounced.run(); // three quick triggers: still nothing until the quiet window passes
    assertEquals(0, count.get());
    Thread.sleep(100);
    debounced.run(); // restarts the window (earliest fire is now ~300ms from the first trigger)
    Thread.sleep(100); // ~200ms after the burst, 100ms before the earliest fire
    assertEquals(0, count.get(), "静默期未满不得执行");

    assertTrue(fired.await(3, TimeUnit.SECONDS), "静默期满必须执行");
    assertEquals(1, count.get());
    Thread.sleep(400);
    assertEquals(1, count.get(), "静默窗口只执行一次");
    debounced.dispose();
    ctx.dispose();
  }

  @Test
  @DisplayName("T97 取消纪律：dispose 丢弃未决执行且后续触发失效；创建域卸载自动回滚")
  void disposeDropsPendingAndRevertsWithTheDomain() throws Exception {
    // Part A: explicit dispose cancels the pending trailing and turns later triggers into no-ops.
    Context ctx = Contexts.create();
    AtomicInteger count = new AtomicInteger();
    Timers.Trigger throttled = Timers.throttle(ctx, count::incrementAndGet, 150);
    throttled.run(); // leading
    throttled.run(); // schedules the trailing
    throttled.dispose();
    Thread.sleep(400);
    assertEquals(1, count.get(), "dispose 必须丢弃未决的尾随执行");
    throttled.run(); // disposed: no-op
    assertEquals(1, count.get());
    Thread.sleep(300);
    assertEquals(1, count.get(), "dispose 后的触发必须是 no-op");
    ctx.dispose();

    // Part B: unloading the plugin that created the handle reverts it automatically.
    Context root = Contexts.create();
    AtomicInteger revived = new AtomicInteger();
    AtomicReference<Timers.Trigger> owned = new AtomicReference<>();
    Disposable plugin =
        root.plugin(
            c -> {
              Timers.Trigger throttled2 = Timers.throttle(c, revived::incrementAndGet, 150);
              owned.set(throttled2);
              throttled2.run(); // leading
              throttled2.run(); // schedules the trailing
              return Disposables.none();
            });
    plugin.dispose(); // unloads the fiber: the handle's guard reverts with the domain
    Thread.sleep(400);
    assertEquals(1, revived.get(), "创建域卸载必须丢弃未决的尾随执行");
    owned.get().run(); // the handle died with the domain
    Thread.sleep(300);
    assertEquals(1, revived.get(), "域卸载后的触发必须是 no-op");
    root.dispose();
  }

  @Test
  @DisplayName("T94 参数校验：负延迟按既有计时器惯例拒绝")
  void negativeDelayIsRejected() {
    Context ctx = Contexts.create();
    assertThrows(IllegalArgumentException.class, () -> Timers.throttle(ctx, () -> {}, -1));
    assertThrows(IllegalArgumentException.class, () -> Timers.throttle(ctx, () -> {}, -1, true));
    assertThrows(IllegalArgumentException.class, () -> Timers.debounce(ctx, () -> {}, -1));
    ctx.dispose();
  }
}
