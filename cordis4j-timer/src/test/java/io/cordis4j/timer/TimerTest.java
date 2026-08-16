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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T30: reversible timers (the JVM form of @cordisjs/timer) - one-shot and periodic callbacks are
 * spawned tasks: handles cancel them, and unloading the owning plugin reverts them.
 */
class TimerTest {

  @Test
  @DisplayName("T30 setTimeout 延迟后执行一次；句柄取消后不再执行")
  void setTimeoutFiresOnceAndCancels() throws Exception {
    Context ctx = Contexts.create();
    AtomicInteger fired = new AtomicInteger();
    Disposable timer = Timers.setTimeout(ctx, fired::incrementAndGet, 60);
    assertEquals(0, fired.get(), "延迟内不得执行");
    Thread.sleep(150);
    assertEquals(1, fired.get(), "延迟后必须执行一次");

    Disposable cancelled = Timers.setTimeout(ctx, fired::incrementAndGet, 60);
    cancelled.dispose();
    Thread.sleep(120);
    assertEquals(1, fired.get(), "取消后不得执行");
  }

  @Test
  @DisplayName("T30 setInterval 周期执行；句柄取消后停止")
  void setIntervalFiresPeriodicallyUntilCancelled() throws Exception {
    Context ctx = Contexts.create();
    AtomicInteger ticks = new AtomicInteger();
    Disposable timer = Timers.setInterval(ctx, ticks::incrementAndGet, 40);
    Thread.sleep(150);
    int during = ticks.get();
    assertTrue(during >= 2, () -> "周期执行至少两次，实际 " + during);

    timer.dispose();
    // The baseline is taken AFTER dispose: an in-flight tick landing just after the
    // cancellation must count toward it, not fail the test (same snapshot race as the
    // domain-revert case below, seen on loaded CI).
    Thread.sleep(90);
    int afterDispose = ticks.get();
    Thread.sleep(120); // more than two intervals: nothing new may fire
    assertEquals(afterDispose, ticks.get(), "取消后必须停止");
  }

  @Test
  @DisplayName("T30 定时器随插件域卸载自动撤销（起始定时器是可逆效应）")
  void timersRevertWithTheirPluginDomain() throws Exception {
    Context ctx = Contexts.create();
    AtomicInteger fired = new AtomicInteger();
    Disposable plugin =
        ctx.plugin(
            c -> {
              Timers.setInterval(c, fired::incrementAndGet, 30);
              Timers.setTimeout(c, fired::incrementAndGet, 60);
              return Disposables.none();
            });
    Thread.sleep(120);
    assertTrue(fired.get() >= 1, "域存活期间定时器必须运行");

    plugin.dispose();
    // The baseline is taken AFTER dispose: an in-flight tick landing just before the
    // reversion must count toward it, not fail the test (snapshot race, seen on slow CI).
    Thread.sleep(90);
    int afterDispose = fired.get();
    Thread.sleep(120); // more than two intervals: nothing new may fire
    assertEquals(afterDispose, fired.get(), "插件卸载必须撤销其定时器");
  }

  @Test
  @DisplayName("T30 timeout 延迟后完成；域卸载提前中断则以 CancellationException 完成")
  void timeoutCompletesOrIsCancelled() throws Exception {
    Context ctx = Contexts.create();
    CompletableFuture<Void> future = Timers.timeout(ctx, 60);
    future.get(2, java.util.concurrent.TimeUnit.SECONDS);
    assertTrue(future.isDone(), "延迟后必须完成");

    CompletableFuture<Void> cancelled = Timers.timeout(ctx, 5000);
    Thread.sleep(60); // let the timer task start its sleep
    ctx.dispose(); // reverts the ambient spawn: the task is interrupted
    assertThrows(
        CancellationException.class,
        () -> cancelled.get(2, java.util.concurrent.TimeUnit.SECONDS),
        "提前中断必须以 CancellationException 完成");
  }

  @Test
  @DisplayName("T30 参数校验：负延迟与 null 拒绝")
  void argumentChecks() {
    Context ctx = Contexts.create();
    assertThrows(IllegalArgumentException.class, () -> Timers.setTimeout(ctx, () -> {}, -1));
    assertThrows(IllegalArgumentException.class, () -> Timers.setInterval(ctx, () -> {}, 0));
    assertThrows(IllegalArgumentException.class, () -> Timers.timeout(ctx, -1));
    assertThrows(NullPointerException.class, () -> Timers.setTimeout(null, () -> {}, 1));
    assertThrows(NullPointerException.class, () -> Timers.setTimeout(ctx, null, 1));
    assertThrows(NullPointerException.class, () -> Timers.setInterval(null, () -> {}, 1));
    assertThrows(NullPointerException.class, () -> Timers.setInterval(ctx, null, 1));
    assertThrows(NullPointerException.class, () -> Timers.timeout(null, 1));
  }
}
