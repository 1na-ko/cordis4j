/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T8: the Service.start/stop hooks (extension D9): start on provide, stop in reverse order. */
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
