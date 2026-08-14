/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T23: regression pin - providing the same service instance twice under one key: disposing the
 * first removal must be a no-op (the binding belongs to the second registration).
 */
class RepeatProvideTest {

  record Single() {}

  @Test
  @DisplayName("T23 同实例重复 provide：第一句柄 dispose 是 no-op，第二句柄才移除绑定")
  void sameInstanceTwice() {
    Context ctx = Contexts.create();
    Single shared = new Single();
    Disposable first = ctx.provide(ServiceKey.of(Single.class), shared);
    Disposable second = ctx.provide(ServiceKey.of(Single.class), shared);

    first.dispose();
    assertTrue(ctx.find(Single.class).isPresent(), "被覆盖的句柄 dispose 必须是 no-op");

    second.dispose();
    assertTrue(ctx.find(Single.class).isEmpty(), "当前句柄 dispose 才移除绑定");
  }

  @Test
  @DisplayName("T23 key/服务类型不匹配在 provide 时立即失败")
  void typeMismatchFailsFast() {
    Context ctx = Contexts.create();
    boolean rejected = false;
    try {
      @SuppressWarnings({"unchecked", "rawtypes"})
      ServiceKey raw = ServiceKey.of(Single.class);
      ctx.provide(raw, "not-a-single");
    } catch (ClassCastException expected) {
      rejected = true;
    }
    assertTrue(rejected, "类型不匹配必须立即抛出 ClassCastException");
    assertEquals(true, ctx.find(Single.class).isEmpty());
  }
}
