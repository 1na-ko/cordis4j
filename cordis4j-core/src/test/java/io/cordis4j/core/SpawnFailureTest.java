/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T78-T81: the failure half of task spawning: a task parked mid-flight is cancelled and joined on
 * unload without the dispose throwing, a task that already failed only warns (never breaks the
 * unloader), an interrupted unloader keeps its interruption flag, and a spawn issued from a domain
 * that died under the caller takes the task over (cancelled in place, reported as CordisException)
 * instead of tracking it into the dead scope (boundary 45).
 *
 * <p>Determinism: a parked callable keeps the FutureTask in state NEW, so {@code cancel(true)}
 * deterministically wins and {@code get()} sees CancellationException; joining the task's own
 * thread happens-after the FutureTask recorded its failure, so {@code get()} then sees
 * ExecutionException; an interrupt flag set before dispose makes {@code get()} throw on entry.
 */
class SpawnFailureTest {

  @Test
  @DisplayName("T78 卸载停泊中的任务：先取消后落地，dispose 不抛（Cancellation 分支）")
  void parkedTaskIsCancelledAndJoinedOnUnload() throws Exception {
    Context ctx = Contexts.create();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch landed = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    Disposable handle =
        ctx.spawn(
            () -> {
              started.countDown();
              try {
                go.await(); // parked mid-flight: cancel(true) must reach it
              } catch (InterruptedException unloaded) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
              } finally {
                landed.countDown();
              }
            });
    assertTrue(started.await(5, TimeUnit.SECONDS), "任务必须先停泊在途");

    assertDoesNotThrow(handle::dispose, "取消停泊任务不得使 dispose 抛出");
    // cancel(true) completes the future immediately; the task's landing is its own latch.
    assertTrue(landed.await(5, TimeUnit.SECONDS), "被取消的任务必须落地");
    assertTrue(interrupted.get(), "dispose 必须中断停泊中的任务");
    assertEquals(1, go.getCount(), "测试自身不得释放任务（只有取消能唤醒它）");
    ctx.dispose();
  }

  @Test
  @DisplayName("T79 已失败的任务在卸载时只告警不传播（Execution 告警分支）")
  void failedTaskWarnsInsteadOfThrowingOnUnload() throws Exception {
    Context ctx = Contexts.create();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    AtomicReference<Thread> carrier = new AtomicReference<>();
    Disposable handle =
        ctx.spawn(
            () -> {
              carrier.set(Thread.currentThread());
              started.countDown();
              try {
                go.await();
              } catch (InterruptedException unused) {
                Thread.currentThread().interrupt();
              }
              throw new IllegalStateException("task-boom");
            });
    assertTrue(started.await(5, TimeUnit.SECONDS), "任务必须先停泊在途");
    go.countDown();
    carrier.get().join(); // happens-after the FutureTask recorded the failure

    assertDoesNotThrow(handle::dispose, "任务自身的失败必须只告警，不得打断卸载");
    ctx.dispose();
  }

  @Test
  @DisplayName("T80 卸载方自带中断标志：dispose 不抛且不吞调用者的中断标志")
  void interruptedUnloaderKeepsItsFlag() throws Exception {
    Context ctx = Contexts.create();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    Disposable handle =
        ctx.spawn(
            () -> {
              started.countDown();
              try {
                go.await();
              } catch (InterruptedException unused) {
                Thread.currentThread().interrupt();
              }
            });
    assertTrue(started.await(5, TimeUnit.SECONDS), "任务必须先停泊在途");

    Thread.currentThread().interrupt();
    assertDoesNotThrow(handle::dispose, "卸载方的中断不得由 dispose 抛出");
    assertTrue(Thread.interrupted(), "dispose 必须保留（并在此清除）调用者的中断标志，供后续 join 不受影响");

    go.countDown();
    ctx.dispose();
  }

  @Test
  @DisplayName("T81 已卸载 fiber 域内再 spawn：任务就地取消，调用者得 CordisException（边界 45）")
  void spawnInsideADisposedDomainTakesOverTheTask() throws Exception {
    Context root = Contexts.create();
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch taskLanded = new CountDownLatch(1);
    AtomicReference<Throwable> takeover = new AtomicReference<>();
    AtomicReference<Thread> secondThread = new AtomicReference<>();
    AtomicBoolean secondInterrupted = new AtomicBoolean();

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
                        root.spawn(
                            () -> {
                              secondThread.set(Thread.currentThread());
                              try {
                                new CountDownLatch(1).await();
                              } catch (InterruptedException cancelled) {
                                secondInterrupted.set(true);
                              }
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

    plugin.dispose(); // unloads the fiber: interrupts the parked task and joins its landing
    assertTrue(taskLanded.await(5, TimeUnit.SECONDS), "被卸载的任务必须落地");

    assertTrue(
        takeover.get() instanceof CordisException,
        "死域内的 spawn 必须以 CordisException 报告接管，而不是把句柄登进已销毁的域");
    assertTrue(
        takeover.get().getCause() instanceof IllegalStateException,
        "原因必须是作用域已销毁的 IllegalStateException");
    Thread cancelled = secondThread.get();
    if (cancelled != null) {
      cancelled.join(TimeUnit.SECONDS.toMillis(5)); // it was interrupted: it must land
    }
    assertTrue(cancelled == null || secondInterrupted.get(), "被接管的任务必须被取消：未启动即取消，或启动后被中断（不得继续停泊）");
    root.dispose();
  }
}
