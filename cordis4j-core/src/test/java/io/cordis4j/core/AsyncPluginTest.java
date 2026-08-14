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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T19: virtual-thread activation and reversible task spawning (paper Section 4.3.3). */
class AsyncPluginTest {

  record Counter(java.util.concurrent.atomic.AtomicInteger value) {}

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
}
