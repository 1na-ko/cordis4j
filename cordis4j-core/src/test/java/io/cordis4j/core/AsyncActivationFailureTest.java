/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T85/T86: pluginAsync dispatches an activation failure by its cause type: a RuntimeException and
 * an Error cross the executor boundary unchanged (only a checked failure wraps into
 * CordisException, which T19 already pins), so the caller sees the failure it threw.
 */
class AsyncActivationFailureTest {

  @Test
  @DisplayName("T85 pluginAsync 的 RuntimeException 激活失败原样直抛（不换类型不加包装）")
  void runtimeActivationFailurePropagatesUnwrapped() {
    Context ctx = Contexts.create();
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                ctx.pluginAsync(
                    c -> {
                      throw new IllegalStateException("runtime-boom");
                    }));
    assertEquals("runtime-boom", failure.getMessage());
    assertTrue(failure.getSuppressed().length == 0, "无域清理失败时不得附加 suppressed");
    ctx.dispose();
  }

  @Test
  @DisplayName("T86 pluginAsync 的 Error 激活失败原样直抛（不包装不吞）")
  void errorActivationFailurePropagatesUnwrapped() {
    Context ctx = Contexts.create();
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                ctx.pluginAsync(
                    c -> {
                      throw new AssertionError("error-boom");
                    }));
    assertEquals("error-boom", failure.getMessage());
    ctx.dispose();
  }
}
