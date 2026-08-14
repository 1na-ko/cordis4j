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

/** T21: the declarative loader (paper Section 5.2.1): id-keyed diff, transactional reconcile. */
class LoaderTest {

  record Greeting(String name) {}

  static final class GreetPlugin implements Plugin {
    private final String name;
    private final List<String> log;

    GreetPlugin(String name, List<String> log) {
      this.name = name;
      this.log = log;
    }

    @Override
    public Disposable apply(Context ctx) {
      log.add("load " + name);
      ctx.provide(ServiceKey.of(Greeting.class, name), new Greeting(name));
      return Disposables.of(() -> log.add("unload " + name));
    }
  }

  @Test
  @DisplayName("T21 首次调和装载全部条目；diff：新增装、消失卸、换实例热重载")
  void reconcileDiffs() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Loader loader = Loader.of(ctx);

    GreetPlugin stable = new GreetPlugin("stable", trace);
    loader.reconcile(
        LoaderConfig.of(
            ComponentEntry.of("a", stable), ComponentEntry.of("b", new GreetPlugin("b1", trace))));
    assertEquals(List.of("load stable", "load b1"), trace);

    loader.reconcile(
        LoaderConfig.of(
            ComponentEntry.of("a", stable), // unchanged: untouched
            ComponentEntry.of("c", new GreetPlugin("c1", trace)))); // new; b vanished
    assertEquals(List.of("load stable", "load b1", "unload b1", "load c1"), trace);

    loader.reconcile(
        LoaderConfig.of(
            ComponentEntry.of("a", new GreetPlugin("stable-v2", trace)), // new instance: reload
            ComponentEntry.of("c", stable)));
    assertTrue(trace.contains("unload stable"), "换实例必须触发热重载（卸旧）");
    assertTrue(trace.contains("load stable-v2"), "换实例必须触发热重载（装新）");
  }

  @Test
  @DisplayName("T21 装载失败时事务回滚：被替换条目恢复，本次新增撤销")
  void failedReconcileRollsBack() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Loader loader = Loader.of(ctx);

    GreetPlugin good = new GreetPlugin("good", trace);
    loader.reconcile(LoaderConfig.of(ComponentEntry.of("a", good)));
    assertEquals(List.of("load good"), trace);

    Plugin broken =
        c -> {
          throw new IllegalStateException("cannot load");
        };
    // a is replaced (new instance) AND bad is added; the bad load fails -> roll everything back
    assertThrows(
        IllegalStateException.class,
        () ->
            loader.reconcile(
                LoaderConfig.of(
                    ComponentEntry.of("a", new GreetPlugin("good-v2", trace)),
                    ComponentEntry.of("bad", broken))));

    assertTrue(trace.contains("unload good"), "替换条目在失败前被卸载");
    assertTrue(trace.lastIndexOf("load good") > trace.indexOf("unload good"), "失败后旧条目必须恢复（事务回滚）");
    assertTrue(ctx.find(ServiceKey.of(Greeting.class, "good")).isPresent(), "回滚后旧供给仍然有效");
  }

  @Test
  @DisplayName("T21 dispose 卸载全部托管组件")
  void disposeUnloadsAll() {
    Context ctx = Contexts.create();
    List<String> trace = new ArrayList<>();
    Loader loader = Loader.of(ctx);
    loader.reconcile(
        LoaderConfig.of(
            ComponentEntry.of("a", new GreetPlugin("a", trace)),
            ComponentEntry.of("b", new GreetPlugin("b", trace))));
    loader.dispose();
    assertEquals(List.of("load a", "load b", "unload b", "unload a"), trace);
  }

  @Test
  @DisplayName("T21 重复 id 拒绝；dispose 后 reconcile 拒绝")
  void invalidStates() {
    Context ctx = Contexts.create();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LoaderConfig.of(
                ComponentEntry.of("x", c -> Disposables.none()),
                ComponentEntry.of("x", c -> Disposables.none())));
    Loader loader = Loader.of(ctx);
    loader.dispose();
    assertThrows(
        IllegalStateException.class,
        () -> loader.reconcile(LoaderConfig.of(ComponentEntry.of("x", c -> Disposables.none()))));
  }
}
