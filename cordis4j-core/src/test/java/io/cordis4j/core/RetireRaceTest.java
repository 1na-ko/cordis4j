/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T48: a declaration retired between the notifyBound selection and the activation must stay down -
 * the raced fiber never runs its body, so no effect of a disposed declaration survives (activate's
 * pre-check sees the retirement instead of trusting the selection's stale snapshot).
 */
class RetireRaceTest {

  static class Tick {}

  static class Sentinel {}

  @Test
  @DisplayName("T48 notifyBound 选定与 activate 之间被 dispose 的声明永不执行 body")
  void racedRetirementNeverActivates() throws Exception {
    Context root = Contexts.create();
    ServiceKey<Tick> key = ServiceKey.of(Tick.class);
    int rounds = 200;
    List<ServiceKey<Sentinel>> sentinels = new ArrayList<>();
    List<WeakReference<Object>> callbacks = new ArrayList<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch go = new CountDownLatch(1);

    Thread provider =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    go.await();
                    for (int i = 0; i < rounds; i++) {
                      Disposable removal = root.provide(key, new Tick());
                      removal.dispose();
                    }
                  } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                  }
                });

    List<Thread> declarers = new ArrayList<>();
    for (int declarer = 0; declarer < 3; declarer++) {
      int lane = declarer;
      Thread thread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      go.await();
                      for (int i = 0; i < rounds; i++) {
                        ServiceKey<Sentinel> sentinel =
                            ServiceKey.of(Sentinel.class, "s" + lane + "-" + i);
                        synchronized (sentinels) {
                          sentinels.add(sentinel);
                        }
                        Object probe = new Object();
                        synchronized (callbacks) {
                          callbacks.add(new WeakReference<>(probe));
                        }
                        // The body plants a sentinel binding: it must appear only through a
                        // legitimate activation, whose unload always reverts it - a raced
                        // activation after retirement would leave the binding orphaned.
                        Disposable declaration =
                            root.inject(
                                key,
                                (ctx, tick) -> {
                                  try {
                                    Thread.sleep(1); // widen the selection-to-activation window
                                  } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                  }
                                  ctx.provide(sentinel, new Sentinel());
                                  return Disposables.of(probe::toString);
                                });
                        declaration.dispose();
                      }
                    } catch (Throwable thrown) {
                      failure.compareAndSet(null, thrown);
                    }
                  });
      declarers.add(thread);
    }

    go.countDown();
    provider.join(TimeUnit.SECONDS.toMillis(60));
    assertFalse(provider.isAlive(), "provider 线程必须在超时内完成");
    for (Thread declarer : declarers) {
      declarer.join(TimeUnit.SECONDS.toMillis(60));
      assertFalse(declarer.isAlive(), "declarer 线程必须在超时内完成");
    }
    assertNull(failure.get(), "压力循环不得产生任何异常");

    List<ServiceKey<Sentinel>> snapshot;
    synchronized (sentinels) {
      snapshot = new ArrayList<>(sentinels);
    }
    for (ServiceKey<Sentinel> sentinel : snapshot) {
      assertTrue(
          root.find(sentinel).isEmpty(),
          "已 dispose 的声明不得残留任何效果（哨兵绑定泄漏说明 raced activation 执行了 body）");
    }

    List<WeakReference<Object>> callbackSnapshot;
    synchronized (callbacks) {
      callbackSnapshot = new ArrayList<>(callbacks);
    }
    for (WeakReference<Object> callback : callbackSnapshot) {
      assertTrue(settle(callback), "已 dispose 声明的回调对象必须可回收");
    }
  }

  private static boolean settle(WeakReference<?> ref) throws InterruptedException {
    for (int i = 0; i < 150 && ref.get() != null; i++) {
      System.gc();
      Thread.sleep(20);
    }
    return ref.get() == null;
  }
}
