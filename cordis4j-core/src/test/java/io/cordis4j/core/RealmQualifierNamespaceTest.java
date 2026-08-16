/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T46: a realm label and a qualifier of the same text are the same store key (D5: the qualifier is
 * a one-dimensional projection of the realm) - an isolated declaration is satisfied by an ambient
 * binding carrying the same qualifier text, drains when that binding withdraws, and re-activates
 * when it appears again. The declaration-side lifecycle is what shares the key space; resolution
 * inside the body keeps speaking the realm's own rewritten lookup.
 */
class RealmQualifierNamespaceTest {

  static class Clock {
    final String name;

    Clock(String name) {
      this.name = name;
    }
  }

  @Test
  @DisplayName("T46 realm 名与 qualifier 同文本即同键：激活、drain、再激活的完整生命周期")
  void realmLabelAndQualifierShareOneKeySpace() {
    Context root = Contexts.create();
    List<String> trace = new ArrayList<>();
    Context realm = root.isolate(Clock.class, "r1");

    Disposable declaration =
        realm.inject(
            Set.of(ServiceKey.of(Clock.class)),
            ctx -> {
              trace.add("activated");
              return Disposables.of(() -> trace.add("drained"));
            });
    assertEquals(List.of(), trace, "无绑定时声明必须保持 INACTIVE");

    Disposable outer = root.provide(ServiceKey.of(Clock.class, "r1"), new Clock("outer"));
    assertEquals(
        List.of("activated"), trace, "isolate 声明重写为 (Clock, \"r1\")，必须被同文本 qualifier 绑定满足");

    outer.dispose();
    assertEquals(
        List.of("activated", "drained"),
        trace,
        "outer 绑定撤回必须经 withdraw((Clock, \"r1\")) drain 该依赖者");

    root.provide(ServiceKey.of(Clock.class, "r1"), new Clock("again"));
    assertEquals(List.of("activated", "drained", "activated"), trace, "同键再提供必须再次激活依赖者");

    declaration.dispose();
    assertEquals(
        List.of("activated", "drained", "activated", "drained"),
        trace,
        "声明退役必须卸载当前激活的 body（最后一次 drained），此后不得再有激活");
  }
}
