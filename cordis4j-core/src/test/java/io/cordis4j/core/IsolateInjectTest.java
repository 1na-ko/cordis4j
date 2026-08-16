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

/**
 * T37: reactive injection inside an isolation realm (the isolate × inject composition): the
 * declaration indexes under the realm-rewritten store key, so bindings appearing, withdrawn, and
 * re-appearing - inside the realm or in the default realm behind it - drive the dependent exactly
 * as they do in the default realm (Algorithm 3 and Theorem 63 through realm redirection).
 */
class IsolateInjectTest {

  record Clock(String name) {}

  @Test
  @DisplayName("T37 隔离域内依赖到位即激活；撤回即排空；恢复即再激活")
  void reactiveLifecycleInsideRealm() {
    Context root = Contexts.create();
    Context realm = root.isolate(Clock.class, "r1");
    List<String> trace = new ArrayList<>();
    realm.inject(
        Clock.class,
        (c, clock) -> {
          trace.add("activated:" + clock.name());
          return Disposables.none();
        });

    Disposable provider =
        realm.plugin(
            c -> {
              c.provide(new Clock("a"));
              return Disposables.none();
            });
    assertEquals(List.of("activated:a"), trace, "域内绑定必须触发激活（notify 走 realm 重写键）");

    provider.dispose();
    assertEquals(List.of("activated:a"), trace, "域内撤回必须排空域内依赖者（withdraw 命中重写键）");

    realm.plugin(
        c -> {
          c.provide(new Clock("b"));
          return Disposables.none();
        });
    assertEquals(List.of("activated:a", "activated:b"), trace, "域内依赖恢复必须再激活");
  }

  @Test
  @DisplayName("T37 撤回排空期间依赖者 teardown 仍可解析被撤回的域内绑定（Theorem 63 经 realm 重写）")
  void withdrawalTeardownStillResolves() {
    Context root = Contexts.create();
    Context realm = root.isolate(Clock.class, "r1");
    List<String> trace = new ArrayList<>();
    realm.inject(
        Clock.class,
        (c, clock) ->
            Disposables.of(() -> trace.add("teardown-sees:" + (c.get(Clock.class) != null))));
    Disposable provider =
        realm.plugin(
            c -> {
              c.provide(new Clock("a"));
              return Disposables.none();
            });

    provider.dispose(); // drains the dependent first; the binding leaves only afterwards

    assertEquals(List.of("teardown-sees:true"), trace, "drain 期间域内绑定必须仍然可解析");
  }

  @Test
  @DisplayName("T37 默认域绑定不满足域内声明（隔离即不可见外层默认绑定，与上游 isolate 语义一致）")
  void defaultBindingHiddenFromRealmDeclaration() {
    Context root = Contexts.create();
    Context realm = root.isolate(Clock.class, "r1");
    List<String> trace = new ArrayList<>();
    realm.inject(
        Clock.class,
        (c, clock) -> {
          trace.add("activated:" + clock.name());
          return Disposables.none();
        });

    Disposable provider =
        root.plugin(
            c -> {
              c.provide(new Clock("root"));
              return Disposables.none();
            });
    assertEquals(List.of(), trace, "域内声明 (Clock,r1) 不得被默认域绑定 (Clock,\"\") 满足");

    provider.dispose();
    assertEquals(List.of(), trace, "默认域撤回也不得影响域内依赖者");

    realm.plugin(
        c -> {
          c.provide(new Clock("inside"));
          return Disposables.none();
        });
    assertEquals(List.of("activated:inside"), trace, "域内绑定出现才激活域内声明");
  }

  @Test
  @DisplayName("T37 域内绑定与默认域键隔离：默认域依赖者不受域内绑定变化影响")
  void realmBindingsIsolatedFromDefault() {
    Context root = Contexts.create();
    Context realm = root.isolate(Clock.class, "r1");
    List<String> trace = new ArrayList<>();
    root.inject(
        Clock.class,
        (c, clock) -> {
          trace.add("default-activated");
          return Disposables.none();
        });

    Disposable realmProvider =
        realm.plugin(
            c -> {
              c.provide(new Clock("a"));
              return Disposables.none();
            });
    assertEquals(List.of(), trace, "域内绑定 (Clock,r1) 不得满足默认域声明 (Clock,\"\")");

    realmProvider.dispose();
    assertEquals(List.of(), trace, "域内撤回也不得影响默认域依赖者");
  }
}
