/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T87: the inject half of the boundary-45 takeover, deterministically. When a fiber unloads, its
 * domain's disposed flag flips before the recovery loop interrupts the parked tasks - so an {@code
 * inject} issued from a task's interrupt handler tracks into an already-disposed scope and takes
 * over: the registrar receives CordisException and the never-activated fiber is retired and
 * unregistered instead of lingering as a reloadable zombie (the inject sibling of T81's spawn).
 */
class InjectDisposeRaceTest {

  static class Tick {}

  @Test
  @DisplayName("T87 已卸载 fiber 域内再 inject：注册接管退休，调用者得 CordisException（边界 45）")
  void injectInsideADisposedDomainTakesOverTheFiber() throws Exception {
    Context root = Contexts.create();
    ServiceKey<Tick> key = ServiceKey.of(Tick.class);
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch taskLanded = new CountDownLatch(1);
    AtomicReference<Throwable> takeover = new AtomicReference<>();
    AtomicBoolean zombieRan = new AtomicBoolean();

    Disposable plugin =
        root.plugin(
            c -> {
              c.spawn(
                  () -> {
                    taskStarted.countDown();
                    try {
                      // Parked until the fiber unloads: the wake-up below runs in a dead domain.
                      new CountDownLatch(1).await();
                    } catch (InterruptedException unloaded) {
                      try {
                        // The declared key is unsatisfied here: the takeover must retire and
                        // unregister the never-activated fiber instead of leaving it indexed.
                        root.inject(
                            key,
                            (ctx, tick) -> {
                              zombieRan.set(true);
                              return Disposables.none();
                            });
                      } catch (Throwable reported) {
                        takeover.set(reported);
                      }
                    } finally {
                      taskLanded.countDown();
                    }
                  });
              return Disposables.none();
            });
    assertTrue(taskStarted.await(5, TimeUnit.SECONDS), "任务必须先停泊在途");

    plugin.dispose(); // 先置位 disposed 标志、后中断：处理器的 inject 必然落入已销毁的域
    assertTrue(taskLanded.await(5, TimeUnit.SECONDS), "被卸载的任务必须落地");

    assertTrue(
        takeover.get() instanceof CordisException,
        "死域内的 inject 必须以 CordisException 报告接管，而不是把句柄登进已销毁的域");
    assertTrue(
        takeover.get().getCause() instanceof IllegalStateException,
        "原因必须是作用域已销毁的 IllegalStateException");

    // notifyBound runs synchronously on the providing thread: a lingering registered fiber would
    // run its body inside this provide call - no polling needed.
    root.provide(key, new Tick());
    assertFalse(zombieRan.get(), "被接管的 fiber 必须已注销：随后的绑定不得唤醒僵尸 body");
    root.dispose();
  }
}
