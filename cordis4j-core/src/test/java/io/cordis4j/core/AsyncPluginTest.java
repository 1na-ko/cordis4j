/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T19: virtual-thread activation and reversible task spawning (paper Section 4.3.3). */
class AsyncPluginTest {

  record Counter(AtomicInteger value) {}

  @Test
  @DisplayName("T19 pluginAsync 在虚拟线程上执行，注册可撤销，返回值清理最先执行")
  void runsOnVirtualThread() throws Exception {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    AtomicReference<String> carrier = new AtomicReference<>();

    Disposable handle =
        ctx.pluginAsync(
            c -> {
              carrier.set(Thread.currentThread().toString());
              c.provide(new Counter(new java.util.concurrent.atomic.AtomicInteger()));
              return Disposables.of(() -> trace.add("cleanup"));
            });
    assertTrue(carrier.get().contains("VirtualThread"), "apply 必须运行在虚拟线程上");
    assertTrue(ctx.find(Counter.class).isPresent());

    handle.dispose();
    assertEquals(List.of("cleanup"), trace);
    assertTrue(ctx.find(Counter.class).isEmpty(), "卸载必须撤销全部注册");
  }

  @Test
  @DisplayName("T19 spawn 的任务在卸载时被中断并等待落地；checked 异常包装后传播")
  void spawnedTaskInterruptedOnUnload() throws Exception {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);

    Disposable handle =
        ctx.pluginAsync(
            c -> {
              c.spawn(
                  () -> {
                    started.countDown();
                    try {
                      Thread.sleep(10_000);
                      trace.add("never");
                    } catch (InterruptedException interrupted) {
                      trace.add("interrupted");
                    } finally {
                      finished.countDown();
                    }
                  });
              return Disposables.none();
            });
    started.await();
    handle.dispose(); // interrupts and joins the spawned task
    finished.await();
    assertEquals(List.of("interrupted"), trace, "任务必须被中断并落地");
  }

  @Test
  @DisplayName("T19 pluginAsync 的 checked 异常以 CordisException 包装传播（激活失败）")
  void checkedFailurePropagates() {
    Context ctx = Contexts.create();
    CordisException failure =
        assertThrows(
            CordisException.class,
            () ->
                ctx.pluginAsync(
                    c -> {
                      throw new Exception("checked-boom");
                    }));
    assertEquals("checked-boom", failure.getCause().getMessage());
  }

  @Test
  @DisplayName("T41 等待激活期间被中断：CordisException 抛出且 fiber 仍可被宿主清理")
  void interruptedActivationStaysDisposable() throws Exception {
    Context ctx = Contexts.create();
    CountDownLatch applying = new CountDownLatch(1);
    CountDownLatch finishApply = new CountDownLatch(1);
    AsyncPlugin plugin =
        new AsyncPlugin() {
          @Override
          public Disposable apply(Context c) throws Exception {
            applying.countDown();
            try {
              finishApply.await(); // hold the activation open
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt(); // ignore the cancel: land anyway
            }
            c.provide(new Counter(new AtomicInteger()));
            return Disposables.none();
          }
        };
    WeakReference<AsyncPlugin> ref = new WeakReference<>(plugin);
    Thread main = Thread.currentThread();
    Thread interrupter =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    applying.await();
                    main.interrupt(); // the waiting caller, not the activation thread
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                });

    boolean reported = false;
    try {
      ctx.pluginAsync(plugin); // blocks on this thread until interrupted
    } catch (CordisException expected) {
      reported = true;
    }
    assertTrue(reported, "中断必须以 CordisException 报告给调用者");
    Thread.interrupted(); // clear the restored flag so later joins are unaffected

    finishApply.countDown(); // the activation lands regardless (the plugin ignores the cancel)
    interrupter.join(TimeUnit.SECONDS.toMillis(2));
    assertFalse(interrupter.isAlive());

    ctx.dispose(); // the ambient-tracked handle must unload the otherwise-orphaned fiber here

    plugin = null;
    assertTrue(settle(ref), "中断路径的 fiber 必须经 ambient 句柄可清理（修复前成为不可卸载的孤儿）");
  }

  private static boolean settle(WeakReference<?> ref) throws InterruptedException {
    for (int i = 0; i < 150 && ref.get() != null; i++) {
      System.gc();
      Thread.sleep(20);
    }
    return ref.get() == null;
  }
}
