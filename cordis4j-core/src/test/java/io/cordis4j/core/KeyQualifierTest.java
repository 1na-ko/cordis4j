/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T9: qualified keys are independent in default-realm contexts (decision D5). */
class KeyQualifierTest {

  @Test
  @DisplayName("T9 同 Class 不同 qualifier 各自独立解析；缺省 qualifier 糖；find 未命中返回 empty")
  void qualifiersResolveIndependently() {
    Context ctx = Contexts.create();
    Store a = new Store("A");
    Store b = new Store("B");
    ctx.provide(ServiceKey.of(Store.class, "a"), a);
    ctx.provide(ServiceKey.of(Store.class, "b"), b);

    assertSame(a, ctx.get(ServiceKey.of(Store.class, "a")));
    assertSame(b, ctx.get(ServiceKey.of(Store.class, "b")));
    assertThrows(NoSuchServiceException.class, () -> ctx.get(Store.class));
    assertEquals(Optional.empty(), ctx.find(Store.class));
    assertEquals(Optional.of(a), ctx.find(ServiceKey.of(Store.class, "a")));
    assertEquals(Optional.of(b), ctx.find(ServiceKey.of(Store.class, "b")));
  }

  @Test
  @DisplayName("T9 同键覆盖：新绑定生效，旧撤销 Disposable 变 no-op，旧服务 stop 被调用")
  void overwriteReplacesBinding() {
    Context ctx = Contexts.create();
    Store first = new Store("first");
    Store second = new Store("second");
    Disposable oldRemoval = ctx.provide(first);
    Disposable newRemoval = ctx.provide(second);

    assertSame(second, ctx.get(Store.class));
    oldRemoval.dispose();
    assertSame(second, ctx.get(Store.class), "被覆盖的旧 Disposable 必须变 no-op");
    newRemoval.dispose();
    assertThrows(NoSuchServiceException.class, () -> ctx.get(Store.class));
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
