/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T11: reactive injection (paper Algorithm 3): activate on satisfaction, unload on withdrawal. */
class InjectReactiveTest {

  record Clock(String name) {}

  record Tick(int n) {}

  @Test
  @DisplayName("T11 依赖未满足时不激活；提供后激活；撤销后自动卸载；再提供后重新激活")
  void reactiveLifecycle() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.inject(
        Clock.class,
        (c, clock) -> {
          trace.add("activated:" + clock.name());
          c.on(Tick.class, tick -> trace.add("tick:" + tick.n()));
          return Disposables.none();
        });

    assertEquals(List.of(), trace, "依赖缺失时 fiber 必须保持 INACTIVE");

    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(new Clock("main"));
              return Disposables.none();
            });
    assertEquals(List.of("activated:main"), trace, "依赖到位后必须立即激活");

    ctx.emit(new Tick(1));
    assertEquals(List.of("activated:main", "tick:1"), trace);

    provider.dispose(); // withdrawal: the dependent unloads reactively
    ctx.emit(new Tick(2));
    assertEquals(
        List.of("activated:main", "tick:1"), trace, "provider 撤销后 dependent 必须已卸载（监听不再触发）");

    ctx.plugin(
        c -> {
          c.provide(new Clock("backup"));
          return Disposables.none();
        });
    assertEquals(
        List.of("activated:main", "tick:1", "activated:backup"), trace, "依赖再次满足后 fiber 必须重新激活");
  }

  @Test
  @DisplayName("T11 inject 句柄 dispose 后永久退役（retired），依赖再满足也不激活")
  void retiredNeverReactivates() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Disposable dependent =
        ctx.inject(
            Clock.class,
            (c, clock) -> {
              trace.add("activated");
              return Disposables.none();
            });

    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(new Clock("main"));
              return Disposables.none();
            });
    assertEquals(List.of("activated"), trace);

    dependent.dispose();
    provider.dispose();
    ctx.plugin(
        c -> {
          c.provide(new Clock("again"));
          return Disposables.none();
        });
    assertEquals(List.of("activated"), trace, "retired fiber 不得重新激活");
  }

  @Test
  @DisplayName("T11 回调返回的 Disposable 最先撤销（fiber 首位清理）")
  void returnedCleanupRunsFirst() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.inject(
        Clock.class,
        (c, clock) -> {
          c.on(Tick.class, tick -> trace.add("tick"));
          return Disposables.of(() -> trace.add("cleanup"));
        });
    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(new Clock("main"));
              return Disposables.none();
            });
    provider.dispose();
    assertEquals(List.of("cleanup"), trace, "fiber 卸载时：回调返回的清理最先执行，随后其余注册按 LIFO 撤销");
  }

  @Test
  @DisplayName("T11 空依赖集合的 fiber 立即激活（声明为空即恒满足）")
  void emptyDependenciesActivateImmediately() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    ctx.inject(
        Set.of(),
        c -> {
          trace.add("activated");
          return Disposables.none();
        });
    assertEquals(List.of("activated"), trace);
  }

  @Test
  @DisplayName("T11 null 回调必须拒绝")
  void nullCallbackRejected() {
    Context ctx = Contexts.create();
    assertThrows(
        NullPointerException.class, () -> ctx.inject(Set.of(ServiceKey.of(Clock.class)), null));
  }
}
