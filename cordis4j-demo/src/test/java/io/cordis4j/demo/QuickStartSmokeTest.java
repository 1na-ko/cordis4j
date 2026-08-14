/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CI 冒烟测试：断言 demo 的端到端行为，而不只是编译通过。
 *
 * <p>验证三条语义：会话内事件链路工作（问候输出）、dispose 后会话监听不再触发（bob 无输出）、 根级服务在会话 dispose 后仍可用（root timer 输出）。
 */
class QuickStartSmokeTest {

  @Test
  @DisplayName("demo 输出问候与根级计时器；dispose 后会话监听静默")
  void quickStartProducesExpectedOutput() {
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      QuickStart.main(new String[0]);
    } finally {
      System.setOut(original);
    }
    String output = captured.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("alice: hello, hi"), () -> "unexpected output: " + output);
    assertTrue(output.contains("root timer = "), () -> "unexpected output: " + output);
    assertFalse(output.contains("bob"), () -> "disposed session listener must not fire: " + output);
  }
}
