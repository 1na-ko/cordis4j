/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.langchain4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CI 冒烟测试：断言 SessionToolDemo 的端到端行为——工具随插件装载出现、热卸载消失、换实现替换， 会话终结后工具集为空——而不只是编译通过。 */
class SessionToolDemoSmokeTest {

  @Test
  @DisplayName("demo 展示会话内工具生命周期；终结后工具集为空")
  void demoRunsTheSessionStory() {
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      SessionToolDemo.main(new String[0]);
    } finally {
      System.setOut(original);
    }
    String output = captured.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("agent: calculator -> 5"), () -> "unexpected output: " + output);
    assertTrue(
        output.contains("agent: calculator after unload -> Optional.empty"),
        () -> "unexpected output: " + output);
    assertTrue(
        output.contains("agent: unit-converter -> 1.0 in = 2.54 cm"),
        () -> "unexpected output: " + output);
    assertTrue(output.contains("tool list -> [calculator]"), () -> "unexpected output: " + output);
    assertTrue(
        output.contains("tool list -> [unit-converter]"), () -> "unexpected output: " + output);
    assertTrue(output.contains("session ended -> []"), () -> "unexpected output: " + output);
    assertFalse(output.contains("Exception"), () -> "unexpected output: " + output);
  }
}
