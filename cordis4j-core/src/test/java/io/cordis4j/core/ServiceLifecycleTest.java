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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T8: the Service.start/stop hooks (extension D9): start on provide, stop in reverse order. */
// T40: start/stop failures leave no orphan binding. T51: an overwrite whose start hook fails
// restores the previous binding instead of evaporating the key.
class ServiceLifecycleTest {

  @Test
  @DisplayName("T8 域内 provide → start()；域撤销 → stop() 逆序")
  void startOnProvideStopReverseOnDispose() {
    Context ctx = Contexts.create();
    List<String> events = new ArrayList<>();
    Disposable domain =
        ctx.plugin(
            pluginCtx -> {
              pluginCtx.provide(
                  ServiceKey.of(HookedService.class, "first"), new HookedService("first", events));
              pluginCtx.provide(
                  ServiceKey.of(HookedService.class, "second"),
                  new HookedService("second", events));
              return Disposables.none();
            });

    assertEquals(List.of("first:start", "second:start"), events, "provide 时必须立即 start");
    domain.dispose();
    assertEquals(
        List.of("first:start", "second:start", "second:stop", "first:stop"),
        events,
        "域撤销时 stop 必须按注册逆序执行");
  }

  @Test
  @DisplayName("T8 覆盖同键绑定时被覆盖服务的 stop 立即执行（契约 §6.4）")
  void overwriteStopsPreviousService() {
    Context ctx = Contexts.create();
    List<String> events = new ArrayList<>();
    ctx.provide(new HookedService("old", events));
    ctx.provide(new HookedService("new", events));
    assertEquals(List.of("old:start", "old:stop", "new:start"), events);
  }

  @Test
  @DisplayName("T8 未实现 Service 的对象不触发钩子")
  void plainObjectsSkipHooks() {
    Context ctx = Contexts.create();
    List<String> events = new ArrayList<>();
    ctx.plugin(
            pluginCtx -> {
              pluginCtx.provide(new PlainService());
              return Disposables.none();
            })
        .dispose();
    assertEquals(List.of(), events, "普通对象不得触发任何钩子");
  }

  @Test
  @DisplayName("T40 start 抛异常：异常传播且绑定不留孤儿（get 不再解析）")
  void startFailureLeavesNoOrphanBinding() {
    Context ctx = Contexts.create();
    class FailingStart implements Service {
      @Override
      public void start() {
        throw new RuntimeException("start-boom");
      }
    }
    class QuietService implements Service {}
    assertThrows(
        RuntimeException.class,
        () -> ctx.provide(ServiceKey.of(FailingStart.class), new FailingStart()));
    assertTrue(ctx.find(ServiceKey.of(FailingStart.class)).isEmpty(), "start 失败后不得残留孤儿绑定");
    ctx.provide(
        ServiceKey.of(QuietService.class), new QuietService()); // a healthy service registers fine
    assertTrue(ctx.find(ServiceKey.of(QuietService.class)).isPresent(), "失败后必须可继续注册其他绑定");
  }

  @Test
  @DisplayName("T40 覆盖旧服务时 stop(旧) 抛异常：异常传播且旧绑定恢复（M8/T51 语义）")
  void overwriteStopFailureRestoresThePreviousBinding() {
    Context ctx = Contexts.create();
    ServiceKey<Service> key = ServiceKey.of(Service.class);
    class BadStop implements Service {
      @Override
      public void stop() {
        throw new RuntimeException("stop-boom");
      }
    }
    Service original = new BadStop();
    ctx.provide(key, original);
    assertThrows(
        RuntimeException.class, () -> ctx.provide(key, new BadStop()), "覆盖时旧服务 stop 抛出的异常必须传播");
    assertTrue(ctx.find(key).isPresent(), "覆盖失败的键必须恢复旧绑定，不得蒸发");
    assertEquals(original, ctx.get(key), "恢复的必须是原绑定实例（依赖者回到覆盖前稳定态）");
  }

  @Test
  @DisplayName("T51 覆盖的 start 失败恢复旧绑定：依赖者不僵尸化，旧服务尽力回到 started")
  void overwriteStartFailureRestoresThePreviousBinding() {
    Context ctx = Contexts.create();
    List<String> events = new ArrayList<>();
    ServiceKey<Service> key = ServiceKey.of(Service.class);
    HookedService oldService = new HookedService("old", events);
    ctx.provide(key, oldService);
    assertEquals(List.of("old:start"), events);

    List<String> dependentTrace = new ArrayList<>();
    Disposable dependent =
        ctx.inject(
            Service.class,
            (c, service) -> {
              dependentTrace.add("activated");
              return Disposables.of(() -> dependentTrace.add("drained"));
            });
    assertEquals(List.of("activated"), dependentTrace, "测试前提：依赖者已激活");

    class FailingStart implements Service {
      @Override
      public void start() {
        throw new RuntimeException("start-boom");
      }
    }
    assertThrows(RuntimeException.class, () -> ctx.provide(key, new FailingStart()));

    assertEquals(
        List.of("old:start", "old:stop", "old:start"),
        events,
        "覆盖失败必须尽力恢复旧服务到 started（事件序列 old:start → old:stop → old:start）");
    assertEquals(oldService, ctx.get(key), "覆盖失败后同键必须解析回旧绑定实例");
    assertEquals(List.of("activated"), dependentTrace, "依赖者不得被 drain：恢复的旧绑定保持依赖者运行（无键蒸发）");
    dependent.dispose();
  }

  private static final class HookedService implements Service {
    private final String name;
    private final List<String> events;

    private HookedService(String name, List<String> events) {
      this.name = name;
      this.events = events;
    }

    @Override
    public void start() {
      events.add(name + ":start");
    }

    @Override
    public void stop() {
      events.add(name + ":stop");
    }
  }

  private static final class PlainService {
    private PlainService() {}
  }
}
