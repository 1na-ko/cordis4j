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
 * T82/T83: the failure half of the reactive scheduler's guards: a declaration retired by an earlier
 * activation of the same notify batch never runs its body (activate's pre-check returns instead of
 * trusting the selection snapshot; the retired INACTIVE fiber leaves the registry - boundaries
 * 38/31), and an unload interrupted while waiting behind an in-flight activation (inertia)
 * propagates IllegalStateException with the interruption flag restored.
 */
class FiberRaceBranchTest {

  static class Tick {}

  @Test
  @DisplayName("T82 同批通知中途退役的声明：activate 早退、body 永不执行、fiber 退出注册（边界 38/31）")
  void retirementInsideANotifyBatchNeverActivates() {
    Context root = Contexts.create();
    ServiceKey<Tick> key = ServiceKey.of(Tick.class);
    AtomicBoolean secondBodyRan = new AtomicBoolean();
    AtomicReference<Disposable> secondDeclaration = new AtomicReference<>();

    // Declared first, so notifyBound activates it first: its body retires the sibling mid-batch.
    Disposable first =
        root.inject(
            key,
            (ctx, tick) -> {
              secondDeclaration.get().dispose();
              return Disposables.none();
            });
    secondDeclaration.set(
        root.inject(
            key,
            (ctx, tick) -> {
              secondBodyRan.set(true);
              return Disposables.none();
            }));

    root.provide(key, new Tick());

    assertFalse(secondBodyRan.get(), "被同批先激活者退役的声明不得执行 body（activate 必须看到退役而非快照）");
    first.dispose();
    root.dispose();
  }

  @Test
  @DisplayName("T83 inertia 等待被中断：IllegalStateException 向上传播且中断标志保留")
  void interruptedInertiaWaitPropagates() throws Exception {
    Context root = Contexts.create();
    ServiceKey<Tick> key = ServiceKey.of(Tick.class);
    CountDownLatch bodyStarted = new CountDownLatch(1);
    CountDownLatch releaseBody = new CountDownLatch(1);
    AtomicReference<Throwable> unloadFailure = new AtomicReference<>();
    AtomicBoolean flagPreserved = new AtomicBoolean();

    Disposable declaration =
        root.inject(
            key,
            (ctx, tick) -> {
              bodyStarted.countDown();
              try {
                releaseBody.await(); // hold the activation in LOADING
              } catch (InterruptedException unexpected) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test body interrupted", unexpected);
              }
              return Disposables.none();
            });

    Thread provider = Thread.ofVirtual().start(() -> root.provide(key, new Tick()));
    assertTrue(bodyStarted.await(5, TimeUnit.SECONDS), "激活主体必须先停泊在途（LOADING）");

    Thread unloader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    declaration.dispose(); // parks behind the in-flight activation
                  } catch (Throwable failure) {
                    unloadFailure.set(failure);
                    flagPreserved.set(Thread.currentThread().isInterrupted());
                  }
                });
    // The fiber stays LOADING until released, so the unload must be inside (or entering) the
    // inertia wait: the interrupt aborts it deterministically either way.
    unloader.interrupt();
    unloader.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(unloader.isAlive(), "被中断的卸载方必须返回");

    assertTrue(
        unloadFailure.get() instanceof IllegalStateException, "中断必须以 IllegalStateException 传播");
    assertTrue(
        unloadFailure.get().getMessage().contains("Interrupted"),
        "错误信息必须指向在途组件的等待：" + unloadFailure.get().getMessage());
    assertTrue(flagPreserved.get(), "中断标志必须被恢复（不得吞掉线程的中断状态）");

    releaseBody.countDown(); // the activation lands
    provider.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(provider.isAlive(), "提供方必须随激活落定返回");
    declaration.dispose(); // now the landed fiber really retires and unloads
    root.dispose();
  }
}
