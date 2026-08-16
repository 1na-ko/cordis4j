/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T38: reentrancy and concurrency of synchronous dispatch (decisions D3/D22 under D19): listeners
 * run outside the bus monitor, so a listener may register, unregister, and re-emit while a dispatch
 * is in flight without deadlocking, a blocked listener does not serialize unrelated event
 * operations, and a once-listener fires exactly once under racing dispatches.
 */
class EventReentrancyTest {

  record Ping(int n) {}

  @Test
  @DisplayName("T38 listener 内再注册并重入分发不死锁，新监听器在下一次分发生效")
  void listenerMayRegisterAndReemit() throws Exception {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.on(
        Ping.class,
        event -> {
          trace.add("first:" + event.n());
          Disposable inner = ctx.on(Ping.class, innerEvent -> trace.add("inner:" + innerEvent.n()));
          if (event.n() < 2) {
            ctx.emit(new Ping(event.n() + 1)); // re-emit while dispatching
          }
          inner.dispose(); // unregister while (possibly recursively) dispatching
        });

    Thread dispatcher = Thread.ofVirtual().start(() -> ctx.emit(new Ping(1)));
    dispatcher.join(TimeUnit.SECONDS.toMillis(2));
    assertFalse(dispatcher.isAlive(), "listener 内注册 + 重入分发不得死锁");
    assertEquals(List.of("first:1", "first:2", "inner:2"), trace, "重入分发必须观察到中途注册的监听器");
  }

  @Test
  @DisplayName("T38 listener 内注销自身注册不死锁，且注销后不再触发")
  void listenerMayDisposeItself() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Disposable[] self = new Disposable[1];
    self[0] =
        ctx.on(
            Ping.class,
            event -> {
              self[0].dispose();
              trace.add("fired:" + event.n());
            });

    ctx.emit(new Ping(1));
    ctx.emit(new Ping(2));

    assertEquals(List.of("fired:1"), trace, "自注销的监听器恰好触发一次");
  }

  @Test
  @DisplayName("T38 阻塞中的 listener 不得阻塞并发注册（回调在 monitor 外执行）")
  void blockedListenerDoesNotSerializeRegistrations() throws Exception {
    Context ctx = Contexts.create();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch registered = new CountDownLatch(1);
    ctx.on(
        Ping.class,
        event -> {
          entered.countDown();
          try {
            release.await(); // block inside the listener
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });

    Thread dispatcher = Thread.ofVirtual().start(() -> ctx.emit(new Ping(1)));
    assertTrue(entered.await(2, TimeUnit.SECONDS), "listener 必须已被进入（分发已开始）");

    Thread registrant =
        Thread.ofVirtual()
            .start(
                () -> {
                  ctx.on(Ping.class, event -> {});
                  registered.countDown();
                });
    assertTrue(registered.await(2, TimeUnit.SECONDS), "阻塞中的 listener 不得持有总线锁（并发注册必须在超时内完成）");

    release.countDown();
    dispatcher.join(TimeUnit.SECONDS.toMillis(2));
    assertFalse(dispatcher.isAlive());
    registrant.join(TimeUnit.SECONDS.toMillis(2));
    assertFalse(registrant.isAlive());
  }

  @Test
  @DisplayName("T38 并发分发下 once 监听器恰好触发一次")
  void onceFiresExactlyOnceUnderRacingDispatches() throws Exception {
    Context ctx = Contexts.create();
    AtomicInteger fired = new AtomicInteger();
    ctx.once(Ping.class, event -> fired.incrementAndGet());

    List<Thread> dispatchers = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      dispatchers.add(Thread.ofVirtual().start(() -> ctx.emit(new Ping(1))));
    }
    for (Thread dispatcher : dispatchers) {
      dispatcher.join(TimeUnit.SECONDS.toMillis(2));
      assertFalse(dispatcher.isAlive(), "并发分发不得死锁");
    }
    assertEquals(1, fired.get(), "竞争分发下 once 必须恰好触发一次");
  }
}
