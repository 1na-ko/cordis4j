/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

/**
 * T35: the Spring integration's container lifecycle - close cascades to the session context and
 * unloads every @CordisService fiber, repeated container cycles leak nothing (prototype beans
 * included), and a withdrawal on close drains dependents with the boundary semantics 13/14 of the
 * core contract.
 */
class SpringLifecycleTest {

  record RootService(String name) {}

  static class Clock {
    final String name;

    Clock(String name) {
      this.name = name;
    }
  }

  @CordisService
  static class DefaultClock extends Clock {
    DefaultClock() {
      super("default");
    }
  }

  @Test
  @DisplayName("T35 close 级联 dispose 会话 context（根服务不受影响）并卸载全部 @CordisService fiber")
  void closeCascadesToTheSession() {
    Context root = Contexts.create();
    root.provide(new RootService("root"));
    Context session = root.fork();

    try (GenericApplicationContext container = new GenericApplicationContext()) {
      container.registerBean(Context.class, () -> session);
      container.registerBean(CordisServiceRegistrar.class);
      container.registerBean(DefaultClock.class);
      container.refresh();

      assertSame("default", session.get(DefaultClock.class).name, "容器运行期间服务必须可见");

      container.close();
      assertThrows(
          IllegalStateException.class,
          () -> session.find(DefaultClock.class),
          "close 必须 dispose 会话 context（其全部 fiber 随之卸载）");
      assertSame("root", root.get(RootService.class).name, "级联不得波及根 context 的服务");
    }
  }

  @Test
  @DisplayName("T35 重复容器周期无句柄泄漏；prototype bean 以 ambient 覆盖语义生效")
  void repeatedCyclesLeakNothing() {
    for (int round = 0; round < 3; round++) {
      try (GenericApplicationContext container = new GenericApplicationContext()) {
        container.registerBean(Context.class, Contexts::create);
        container.registerBean(CordisServiceRegistrar.class);
        container.registerBean(DefaultClock.class);
        container.registerBean(
            DefaultClock.class, DefaultClock::new, definition -> definition.setScope("prototype"));
        container.refresh();

        Context ctx = container.getBean(Context.class);
        DefaultClock singleton = container.getBean(DefaultClock.class);
        assertEquals("default", ctx.get(DefaultClock.class).name, "第 " + round + " 轮：服务必须可用");

        DefaultClock prototype = container.getBean(DefaultClock.class);
        assertNotSame(singleton, prototype, "prototype 必须产生新实例");
        assertSame(prototype, ctx.get(DefaultClock.class), "prototype 提供以 ambient 覆盖语义胜出（D12）");

        container.close();
        assertThrows(
            IllegalStateException.class,
            () -> ctx.find(DefaultClock.class),
            "第 " + round + " 轮：close 必须 dispose 该轮的 context");
      }
    }
  }

  @Test
  @DisplayName("T35 close 撤回触发 withdrawal drain：依赖 fiber 卸载且 teardown 仍解析（边界 13/14）")
  void withdrawalDrainOnClose() {
    try (GenericApplicationContext container = new GenericApplicationContext()) {
      container.registerBean(Context.class, Contexts::create);
      container.registerBean(CordisServiceRegistrar.class);
      container.registerBean(DefaultClock.class);
      container.refresh();

      Context ctx = container.getBean(Context.class);
      List<String> trace = new ArrayList<>();
      Disposable dependent =
          ctx.inject(
              DefaultClock.class,
              (c, clock) -> {
                trace.add("activated:" + clock.name);
                return Disposables.of(
                    () -> trace.add("cleanup sees: " + c.get(DefaultClock.class).name));
              });
      assertEquals(List.of("activated:default"), trace, "容器运行时依赖 fiber 必须激活");

      container
          .close(); // the registrar withdraws the binding: dependents drain (13), teardown resolves
      // (14)
      assertEquals(
          List.of("activated:default", "cleanup sees: default"),
          trace,
          "撤回必须卸载依赖 fiber，且 teardown 仍能解析被撤绑定");
      dependent.dispose(); // idempotent after the reactive unload
    }
  }
}
