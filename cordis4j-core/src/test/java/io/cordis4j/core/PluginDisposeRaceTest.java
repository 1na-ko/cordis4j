/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T65/T66: a registration whose ambient tracking loses the race against a concurrent dispose of its
 * context recovers in place instead of leaking - the landed fiber retires and unloads (bindings
 * withdrawn, spawned tasks cancelled and joined), and the caller receives a CordisException rather
 * than the scope's raw IllegalStateException. This generalizes boundary 34's interrupted-caller
 * takeover to every untracked registration (D29, boundary 45; the 0.4.1 QA review's F1/F2).
 */
class PluginDisposeRaceTest {

  /** A service whose reversion is observable: the takeover must withdraw provided bindings. */
  static final class Spy implements Service {
    final AtomicBoolean stopped = new AtomicBoolean();

    @Override
    public void stop() {
      stopped.set(true);
    }
  }

  /** A sentinel binding whose reversion signals that the ambient scope has been drained. */
  static final class Signal implements Service {
    final CountDownLatch stopped = new CountDownLatch(1);

    @Override
    public void stop() {
      stopped.countDown();
    }
  }

  @Test
  @DisplayName("T65 激活在途撞上 dispose：孤儿 fiber 就地退休卸载（绑定撤回、spawn 取消、dispose 完成）")
  void asyncActivationRacingDisposeTakesOverTheOrphan() throws Exception {
    Context root = Contexts.create();
    Signal signal = new Signal();
    root.provide(ServiceKey.of(Signal.class), signal);
    Spy spy = new Spy();
    CountDownLatch bodyStarted = new CountDownLatch(1);
    CountDownLatch releaseBody = new CountDownLatch(1);
    CountDownLatch taskLanded = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    root.pluginAsync(
                        c -> {
                          c.provide(ServiceKey.of(Spy.class), spy);
                          c.spawn(
                              () -> {
                                try {
                                  Thread.sleep(60_000);
                                } catch (InterruptedException interrupted) {
                                  // land on cancel: the takeover must reach this task
                                } finally {
                                  taskLanded.countDown();
                                }
                              });
                          bodyStarted.countDown();
                          releaseBody.await();
                          return Disposables.none();
                        });
                  } catch (Throwable thrown) {
                    failure.set(thrown);
                  }
                });
    assertTrue(bodyStarted.await(5, TimeUnit.SECONDS), "激活主体必须先停泊在途");

    Thread closer = Thread.ofVirtual().start(root::dispose);
    assertTrue(signal.stopped.await(5, TimeUnit.SECONDS), "dispose 必须先跑完 ambient 阶段");
    releaseBody.countDown(); // the activation now lands into a disposing context

    caller.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(caller.isAlive(), "pluginAsync 调用者必须返回（接管卸载不得挂起）");
    assertTrue(failure.get() instanceof CordisException, "竞态必须以 CordisException 报告");
    assertTrue(
        failure.get().getCause() instanceof IllegalStateException,
        "原因必须是作用域已销毁的 IllegalStateException");

    assertTrue(taskLanded.await(5, TimeUnit.SECONDS), "接管必须取消并回收 fiber 内 spawn 的任务");
    assertTrue(spy.stopped.get(), "接管必须撤回孤儿 fiber 提供的绑定");

    closer.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(closer.isAlive(), "dispose 必须完成（修复前泄漏 fiber 的失控任务会挂死 executor 关闭）");
    assertDoesNotThrow(root::dispose, "二次 dispose 必须幂等");
  }

  @Test
  @DisplayName("T66 同步 plugin 撞上 dispose：落地后接管，调用者得 CordisException 而非泄漏")
  void syncPluginRacingDisposeTakesOverTheOrphan() throws Exception {
    Context root = Contexts.create();
    Signal signal = new Signal();
    root.provide(ServiceKey.of(Signal.class), signal);
    Spy spy = new Spy();
    CountDownLatch bodyStarted = new CountDownLatch(1);
    CountDownLatch releaseBody = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    root.plugin(
                        c -> {
                          c.provide(ServiceKey.of(Spy.class), spy);
                          bodyStarted.countDown();
                          try {
                            releaseBody.await();
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("test body interrupted", interrupted);
                          }
                          return Disposables.none();
                        });
                  } catch (Throwable thrown) {
                    failure.set(thrown);
                  }
                });
    assertTrue(bodyStarted.await(5, TimeUnit.SECONDS), "激活主体必须先停泊在途");

    Thread closer = Thread.ofVirtual().start(root::dispose);
    assertTrue(signal.stopped.await(5, TimeUnit.SECONDS), "dispose 必须先跑完 ambient 阶段");
    releaseBody.countDown(); // the activation now lands (on the caller thread) into a dead scope

    caller.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(caller.isAlive());
    assertTrue(failure.get() instanceof CordisException, "竞态必须以 CordisException 报告");
    assertTrue(
        failure.get().getCause() instanceof IllegalStateException,
        "原因必须是作用域已销毁的 IllegalStateException");
    assertTrue(spy.stopped.get(), "接管必须撤回已落地的绑定");

    closer.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(closer.isAlive());
    assertDoesNotThrow(root::dispose, "二次 dispose 必须幂等");
  }
}
