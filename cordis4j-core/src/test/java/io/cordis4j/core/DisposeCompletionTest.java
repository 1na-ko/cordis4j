/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T50: dispose-completion guarantees on failure paths - a component whose teardown throws still
 * lands its realm accounting (the drained realm really discards, a later reload gets a fresh one),
 * and a context dispose whose ambient phase throws still closes the executor behind it.
 */
class DisposeCompletionTest {

  static class Marker implements Service {}

  @Test
  @DisplayName("T50 隔离域组件 teardown 抛异常：异常传播且域计数照常归零，同 realm 重装载换新域")
  void loaderUnloadAccountingSurvivesTeardownFailure() {
    Context root = Contexts.create();
    Loader loader = Loader.of(root);
    AtomicReference<Context> firstDomain = new AtomicReference<>();
    AtomicReference<Context> secondDomain = new AtomicReference<>();

    loader.reconcileTree(
        List.of(
            new ComponentSpec.Isolate(
                Marker.class,
                "iso",
                List.of(
                    new ComponentSpec.Entry(
                        "c1",
                        ctx -> {
                          firstDomain.set(ctx);
                          return Disposables.of(
                              () -> {
                                throw new IllegalStateException("teardown failed");
                              });
                        })))));
    assertTrue(firstDomain.get() != null, "装载后插件必须已进入隔离域的 derived context");

    DisposeException failure =
        assertThrows(DisposeException.class, () -> loader.reconcileTree(List.of()));
    assertTrue(
        failure.getSuppressed().length > 0
            && failure.getSuppressed()[0] instanceof IllegalStateException,
        "组件 teardown 的失败必须作为 suppressed 附着传播");

    // The accounting landed despite the failure, so the drained realm was discarded (its derived
    // context disposed) instead of lingering as a zombie entry the next reconcile reuses.
    loader.reconcileTree(
        List.of(
            new ComponentSpec.Isolate(
                Marker.class,
                "iso",
                List.of(
                    new ComponentSpec.Entry(
                        "c1",
                        ctx -> {
                          secondDomain.set(ctx);
                          return Disposables.none();
                        })))));
    assertNotSame(
        firstDomain.get(), secondDomain.get(), "僵尸域必须被丢弃：计数漂移会让同 realm 重装载复用未清的 derived context");
  }

  @Test
  @DisplayName("T50 ambient teardown 抛异常时 executor 仍被关闭：in-flight 激活被等待落地")
  void disposeClosesTheExecutorEvenWhenAmbientTeardownThrows() throws Exception {
    Context root = Contexts.create();
    CountDownLatch activated = new CountDownLatch(1);
    CountDownLatch finishApply = new CountDownLatch(1);
    CountDownLatch ambientDrained = new CountDownLatch(1);
    AtomicReference<Throwable> disposeFailure = new AtomicReference<>();
    AtomicReference<Thread> carrier = new AtomicReference<>();

    // The activation runs on the root executor with no ambient handle: only dispose's executor
    // close() can wait for it once the ambient phase has already failed.
    Thread caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    root.pluginAsync(
                        c -> {
                          carrier.set(Thread.currentThread());
                          activated.countDown();
                          finishApply.await(); // hold the activation open
                          return Disposables.none();
                        });
                  } catch (Throwable thrown) {
                    // the late track() after the dispose lands here; the caller is not under test
                  }
                });
    activated.await(2, TimeUnit.SECONDS);

    root.provide(
        ServiceKey.of(Marker.class),
        new Marker() {
          @Override
          public void stop() {
            ambientDrained.countDown(); // signals: dispose passed its ambient phase
            throw new IllegalStateException("ambient teardown failed");
          }
        });

    Thread closer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    root.dispose();
                  } catch (Throwable thrown) {
                    disposeFailure.set(thrown);
                  }
                });

    ambientDrained.await(2, TimeUnit.SECONDS);
    Thread.sleep(200); // let a close-skipping dispose surface its failure early
    assertNull(disposeFailure.get(), "dispose 必须仍在等待 executor 落地，而不是 ambient 失败后提前返回（executor 未关）");

    finishApply.countDown(); // release the activation: the executor close now completes
    closer.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(closer.isAlive(), "dispose 必须在激活落定后完成");
    assertTrue(
        disposeFailure.get() instanceof DisposeException, "ambient 阶段的失败仍必须以 DisposeException 传播");

    Thread applyThread = carrier.get();
    for (int i = 0; i < 250 && applyThread.isAlive(); i++) {
      Thread.sleep(20);
    }
    assertFalse(applyThread.isAlive(), "executor 必须被关闭：激活载体线程最终落地");
    caller.join(TimeUnit.SECONDS.toMillis(2));
    assertFalse(caller.isAlive());
  }
}
