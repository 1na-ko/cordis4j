/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T10: isolation derives an independent realm for a key (paper, Section 5.1.2). */
class IsolateRealmTest {

  @Test
  @DisplayName("T10 isolate(type, realm) 派生上下文解析独立绑定；父不受影响")
  void isolateRedirectsBindingToChildRealm() {
    Context root = Contexts.create();
    Store rootStore = new Store("root");
    root.provide(rootStore);

    Context isolated = root.isolate(Store.class, "r");
    isolated.provide(new Store("child"));

    assertNotSame(rootStore, isolated.get(Store.class), "隔离域内必须解析到子绑定");
    assertSame(rootStore, root.get(Store.class), "父必须不受影响");
  }

  @Test
  @DisplayName("T10 dispose 子即丢弃绑定；派生域卸载时级联丢弃")
  void disposeChildDiscardsBinding() {
    Context root = Contexts.create();
    Store rootStore = new Store("root");
    root.provide(rootStore);

    Context isolated = root.isolate(Store.class, "r");
    isolated.provide(new Store("child"));
    isolated.dispose();

    Context sibling = root.isolate(Store.class, "r");
    assertSame(rootStore, sibling.get(Store.class), "子绑定必须随子上下文被丢弃");
  }

  private static final class Store {
    private final String name;

    private Store(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "Store[" + name + "]";
    }
  }
}
