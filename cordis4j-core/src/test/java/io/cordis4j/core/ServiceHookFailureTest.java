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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T75-T77: the failure half of the service-hook rollback (boundary 33) and of the activation's own
 * cleanup: a best-effort restart of the overwritten service that itself fails attaches as a
 * suppressed exception, a fiber-supplied overwrite restores the previous binding and unrecords the
 * supply bookkeeping, and an activation failure whose domain cleanup also fails aggregates both
 * layers instead of hiding either.
 */
class ServiceHookFailureTest {

  @Test
  @DisplayName("T75 覆盖失败且旧服务重启也失败：主异常传播，重启失败以 suppressed 附着（边界 33）")
  void failingBestEffortRestartIsSuppressedOntoTheStartFailure() {
    Context ctx = Contexts.create();
    ServiceKey<Service> key = ServiceKey.of(Service.class);
    List<String> events = new ArrayList<>();
    AtomicInteger starts = new AtomicInteger();
    Service old =
        new Service() {
          @Override
          public void start() {
            if (starts.incrementAndGet() == 2) {
              throw new IllegalStateException("restart-boom"); // only the best-effort restart
            }
            events.add("old:start");
          }

          @Override
          public void stop() {
            events.add("old:stop");
          }
        };
    ctx.provide(key, old);
    assertEquals(List.of("old:start"), events);

    List<String> dependentTrace = new ArrayList<>();
    Disposable dependent =
        ctx.inject(
            Service.class,
            (c, service) -> {
              dependentTrace.add("activated");
              return Disposables.of(() -> dependentTrace.add("drained"));
            });

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                ctx.provide(
                    key,
                    new Service() {
                      @Override
                      public void start() {
                        throw new RuntimeException("start-boom");
                      }
                    }));
    assertEquals("start-boom", failure.getMessage(), "主异常必须仍是新绑定 start 的失败");
    assertTrue(
        failure.getSuppressed().length == 1
            && failure.getSuppressed()[0] instanceof IllegalStateException
            && "restart-boom".equals(failure.getSuppressed()[0].getMessage()),
        "尽力重启的失败必须以 suppressed 附着，而非吞掉或替换主异常");
    assertEquals(old, ctx.get(key), "绑定必须已恢复旧实例（token 与 owner 完好，键不蒸发）");
    assertEquals(List.of("old:start", "old:stop"), events, "重启失败后旧服务停留在 stopped（尽力而为，不虚构 started）");
    assertEquals(List.of("activated"), dependentTrace, "依赖方不得失联：恢复的旧绑定保持依赖者运行");

    dependent.dispose();
    ctx.dispose();
  }

  @Test
  @DisplayName("T76 fiber 内覆盖的 start 失败：旧绑定恢复、供给簿记回撤、异常经激活失败传播（边界 33）")
  void fiberSuppliedOverwriteFailureUnrecordsTheSupply() {
    Context ctx = Contexts.create();
    ServiceKey<Service> key = ServiceKey.of(Service.class);
    List<String> events = new ArrayList<>();
    Service old =
        new Service() {
          @Override
          public void start() {
            events.add("old:start");
          }

          @Override
          public void stop() {
            events.add("old:stop");
          }
        };
    ctx.provide(key, old);

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                ctx.plugin(
                    c -> {
                      c.provide(
                          key,
                          new Service() {
                            @Override
                            public void start() {
                              throw new RuntimeException("start-boom");
                            }
                          });
                      return Disposables.none();
                    }));
    assertEquals("start-boom", failure.getMessage(), "fiber 内的 start 失败必须经激活失败路径传播");
    assertEquals(
        List.of("old:start", "old:stop", "old:start"),
        events,
        "覆盖失败必须恢复旧绑定并尽力重启旧服务（fiber 的供给簿记随之回撤）");
    assertEquals(old, ctx.get(key), "恢复的必须是原绑定实例");
    ctx.dispose();
  }

  @Test
  @DisplayName("T77 激活失败且域清理也失败：清理失败聚合为 DisposeException 附着（失败路由的失败半边）")
  void activationFailureWithFailingDomainCleanupAggregatesBothLayers() {
    Context ctx = Contexts.create();
    ServiceKey<Service> key = ServiceKey.of(Service.class);
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                ctx.plugin(
                    c -> {
                      c.provide(
                          key,
                          new Service() {
                            @Override
                            public void stop() {
                              throw new RuntimeException("stop-boom");
                            }
                          });
                      throw new IllegalStateException("apply-boom");
                    }));
    assertEquals("apply-boom", failure.getMessage(), "主异常必须是激活失败本身");
    assertTrue(
        failure.getSuppressed().length == 1
            && failure.getSuppressed()[0] instanceof DisposeException,
        "域清理失败必须聚合为 DisposeException 附着在激活异常上");
    DisposeException cleanup = (DisposeException) failure.getSuppressed()[0];
    assertTrue(
        cleanup.getSuppressed().length == 1
            && "stop-boom".equals(cleanup.getSuppressed()[0].getMessage()),
        "清理内部的失败必须再聚合一层，两层都可见");
    assertTrue(ctx.find(key).isEmpty(), "失败的激活不得残留绑定");
    ctx.dispose();
  }
}
