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

/** CI 冒烟测试：逐个断言四个行为演示的关键输出（反应式组合 / 多租户 / 热重载 / agent harness）。 */
class DemoSmokeTest {

  private static String run(Runnable main) {
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      main.run();
    } finally {
      System.setOut(original);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("反应式组合：缓存随数据库装卸而上下线，恢复后重新上线")
  void reactiveComposition() {
    String out = run(() -> ReactiveCompositionDemo.main(new String[0]));
    assertTrue(out.contains("[cache] online on postgres"), () -> out);
    assertTrue(out.contains("[cache] offline"), () -> out);
    assertTrue(out.contains("[cache] online on postgres-recovered"), () -> out);
    assertTrue(out.contains("cached(postgres) <- select 1"), () -> out);
  }

  @Test
  @DisplayName("多租户：同类型服务按 realm 隔离解析；销毁租户不影响其他租户")
  void multiTenant() {
    String out = run(() -> MultiTenantDemo.main(new String[0]));
    assertTrue(out.contains("alice sees: key-of-alice"), () -> out);
    assertTrue(out.contains("bob   sees: key-of-bob"), () -> out);
    assertTrue(out.contains("app   sees secrets? false"), () -> out);
    assertTrue(out.contains("alice session inherits: key-of-alice"), () -> out);
    assertTrue(out.contains("after alice.dispose, bob still: key-of-bob"), () -> out);
  }

  @Test
  @DisplayName("热重载：diff 装卸/重载，失败配置回滚到上一版")
  void hotReload() {
    String out = run(() -> HotReloadDemo.main(new String[0]));
    assertTrue(out.contains("load greet"), () -> out);
    assertTrue(out.contains("unload greet"), () -> out);
    assertTrue(out.contains("load search-v2"), () -> out);
    assertTrue(
        out.contains("reconcile failed: cannot load broken plugin -> rolled back"), () -> out);
    assertTrue(out.contains("search still serves: search-v2"), () -> out);
  }

  @Test
  @DisplayName("agent harness：工具反应式上下线，会话销毁中断 loop 并整体撤销")
  void agentHarness() throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      AgentHarnessDemo.main(new String[0]);
    } finally {
      System.setOut(original);
    }
    String out = captured.toString(StandardCharsets.UTF_8);
    assertTrue(out.contains("tool up: calculator"), () -> out);
    assertTrue(out.contains("tool up: web_search"), () -> out);
    assertTrue(out.contains("tool down: calculator"), () -> out);
    assertTrue(out.contains("tools=[web_search]"), () -> out);
    assertTrue(out.contains("tool down: web_search"), () -> out);
    assertFalse(out.contains("never"), () -> out);
  }
}
