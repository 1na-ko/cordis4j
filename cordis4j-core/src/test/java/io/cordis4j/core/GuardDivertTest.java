/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T20: the guard of the effect iterator (paper Section 4.3.2): diversion is observable. */
class GuardDivertTest {

  record Resource() {}

  private static boolean diverted(Context ctx) {
    Optional<FiberHandle> fiber = ctx.currentFiber();
    return fiber.map(FiberHandle::isDiverted).orElse(false);
  }

  @Test
  @DisplayName("T20 spawn 任务内轮询 currentFiber：句柄 dispose 后观察到 diversion 并落地")
  void diversionObservableInTask() throws Exception {
    Context ctx = Contexts.create();
    AtomicBoolean sawDiversion = new AtomicBoolean();
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch taskDone = new CountDownLatch(1);

    Disposable handle =
        ctx.pluginAsync(
            c -> {
              c.spawn(
                  () -> {
                    taskStarted.countDown();
                    while (!diverted(c)) {
                      try {
                        Thread.sleep(5);
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break; // verify diversion below, then land
                      }
                    }
                    sawDiversion.set(diverted(c));
                    taskDone.countDown();
                  });
              return Disposables.none();
            });

    assertTrue(taskStarted.await(10, TimeUnit.SECONDS), "任务必须启动");
    assertFalse(ctx.currentFiber().isPresent(), "主线程不在任何 fiber 内");
    handle.dispose();
    assertTrue(taskDone.await(10, TimeUnit.SECONDS), "任务必须在卸载时落地");
    assertTrue(sawDiversion.get(), "任务退出前必须观察到 diversion");
  }

  @Test
  @DisplayName("T20 声明式 fiber 的依赖被撤销后 guard 报告 diversion")
  void diversionOnWithdrawal() throws Exception {
    Context ctx = Contexts.create();
    AtomicBoolean hasFiber = new AtomicBoolean();
    AtomicBoolean diverted = new AtomicBoolean();
    CountDownLatch activated = new CountDownLatch(1);
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch readDone = new CountDownLatch(1);

    ctx.inject(
        Resource.class,
        (c, resource) -> {
          c.spawn(
              () -> {
                taskStarted.countDown();
                try {
                  Thread.sleep(60_000); // until the drain interrupts the task
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
                hasFiber.set(c.currentFiber().isPresent());
                diverted.set(diverted(c)); // the fiber is unloading: target moved away
                readDone.countDown();
              });
          activated.countDown();
          return Disposables.none();
        });
    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(new Resource());
              return Disposables.none();
            });
    assertTrue(activated.await(10, TimeUnit.SECONDS));
    assertTrue(taskStarted.await(10, TimeUnit.SECONDS), "任务必须已进入睡眠");
    provider.dispose(); // withdrawal drains the fiber, interrupting the task
    assertTrue(readDone.await(10, TimeUnit.SECONDS), "任务必须完成 diversion 读取");
    assertTrue(diverted.get(), "依赖撤销后 guard 必须报告 diversion (hasFiber=" + hasFiber.get() + ")");
  }
}
