/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T22: concurrent access smoke - registrations and lookups from many threads stay consistent. */
class ConcurrentAccessTest {

  record Slot(int index) {}

  @Test
  @DisplayName("T22 多线程并发 provide/get：无异常且最终一致")
  void concurrentProvideGet() throws Exception {
    Context ctx = Contexts.create();
    int threads = 8;
    int perThread = 200;
    AtomicInteger failures = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(threads);
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int t = 0; t < threads; t++) {
        int base = t * perThread;
        pool.submit(
            () -> {
              try {
                for (int i = 0; i < perThread; i++) {
                  int index = base + i;
                  ctx.provide(ServiceKey.of(Slot.class, "slot-" + index), new Slot(index));
                  Slot resolved = ctx.get(ServiceKey.of(Slot.class, "slot-" + index));
                  if (resolved.index() != index) {
                    failures.incrementAndGet();
                  }
                }
              } catch (RuntimeException failure) {
                failures.incrementAndGet();
              } finally {
                done.countDown();
              }
            });
      }
      done.await(30, TimeUnit.SECONDS);
    }
    assertEquals(0, failures.get(), "并发注册与解析必须一致");
    for (int index = 0; index < threads * perThread; index += threads * perThread / 8) {
      assertEquals(
          index, ctx.get(ServiceKey.of(Slot.class, "slot-" + index)).index(), "抽样解析必须返回正确绑定");
    }
  }
}
