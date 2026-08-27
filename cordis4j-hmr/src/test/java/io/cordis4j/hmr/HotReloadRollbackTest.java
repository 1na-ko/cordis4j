/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.hmr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T67/T68: the failure half of the transactional install (paper Algorithm 10, the JVM form of
 * Section 5.2.2): a load whose plugin apply fails after touching the context rolls back every layer
 * - the core reverts the partial fiber domain, and the loader unrecords the source and detaches the
 * code - so the same id loads again as if nothing happened; the reload guards reject unknown ids
 * before any code moves.
 */
class HotReloadRollbackTest {

  @TempDir Path dir;

  private static String greetingPlugin(String className, String value) {
    return "package p1;\n"
        + "public class "
        + className
        + " implements io.cordis4j.core.Plugin {\n"
        + "  public io.cordis4j.core.Disposable apply(io.cordis4j.core.Context ctx) {\n"
        + "    ctx.provide(\""
        + value
        + "\");\n"
        + "    return io.cordis4j.core.Disposables.none();\n"
        + "  }\n"
        + "}\n";
  }

  /** Provides a binding first, then fails: the rollback must reach the half-applied state. */
  private static String halfAppliedPlugin() {
    return "package p1;\n"
        + "public class HalfAppliedPlugin implements io.cordis4j.core.Plugin {\n"
        + "  public io.cordis4j.core.Disposable apply(io.cordis4j.core.Context ctx) {\n"
        + "    ctx.provide(\"half\");\n"
        + "    throw new RuntimeException(\"apply-boom\");\n"
        + "  }\n"
        + "}\n";
  }

  private static String numberPlugin(long value) {
    return "package p1;\n"
        + "public class NumberPlugin implements io.cordis4j.core.Plugin {\n"
        + "  public io.cordis4j.core.Disposable apply(io.cordis4j.core.Context ctx) {\n"
        + "    ctx.provide(Long.valueOf("
        + value
        + "L));\n"
        + "    return io.cordis4j.core.Disposables.none();\n"
        + "  }\n"
        + "}\n";
  }

  private Path jar(String name, String source) throws IOException {
    return TestJars.compileJar(
        dir, name, List.of(new TestJars.Source("p1.GreetingPlugin", source)));
  }

  /** Loops gc until the handle's code is collected, up to {@code maxMillis}. */
  private static boolean settle(PluginHandle handle, long maxMillis) throws InterruptedException {
    long deadline = System.currentTimeMillis() + maxMillis;
    while (System.currentTimeMillis() < deadline) {
      if (handle.collected()) {
        return true;
      }
      System.gc();
      Thread.sleep(25);
    }
    return handle.collected();
  }

  @Test
  @DisplayName("T67 装载失败的事务回滚：半途绑定撤回、来源与代码退录，同 id 可重试（Algorithm 10 失败半边）")
  void failedInstallRollsBackAndAllowsRetry() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);

    Path halfApplied =
        TestJars.compileJar(
            dir,
            "half-applied.jar",
            List.of(new TestJars.Source("p1.HalfAppliedPlugin", halfAppliedPlugin())));
    assertThrows(
        RuntimeException.class,
        () -> hrl.load("greeting", halfApplied, "p1.HalfAppliedPlugin"),
        "apply 失败必须原样传播");
    assertTrue(ctx.find(String.class).isEmpty(), "半途提供的绑定必须随 fiber 域回滚撤回");
    assertTrue(hrl.ids().isEmpty(), "失败的装载必须退录来源（sources.remove）");
    PluginHandle failed = hrl.handle("greeting").orElseThrow();
    assertThrows(
        IllegalStateException.class,
        failed::plugin,
        "失败的装载必须卸装代码（registry.uninstall：句柄 detach、插件引用断开）");

    hrl.load("greeting", jar("good.jar", greetingPlugin("GreetingPlugin", "v1")));
    assertEquals("v1", ctx.get(String.class), "回滚后同 id 必须可以重新装载");
    assertEquals(List.of("greeting"), hrl.ids());

    // The recovered system keeps its full lifecycle: a sibling id, a reload that must not
    // disturb it, and an unload that keeps the sibling's entry in the reconciled config.
    hrl.load(
        "second",
        TestJars.compileJar(
            dir, "second.jar", List.of(new TestJars.Source("p1.NumberPlugin", numberPlugin(42)))));
    assertEquals(42L, ctx.get(Long.class), "回滚后的系统必须仍可装载兄弟组件");

    hrl.reload("greeting", jar("v2.jar", greetingPlugin("GreetingPlugin", "v2")));
    assertEquals("v2", ctx.get(String.class), "回滚后的系统必须仍可正常重载");
    assertEquals(42L, (long) ctx.get(Long.class), "重载不得扰动兄弟组件");

    hrl.unload("second");
    assertTrue(ctx.find(Long.class).isEmpty(), "卸载必须撤回兄弟组件的绑定");
    assertEquals("v2", ctx.get(String.class), "卸载不得扰动其余组件");

    hrl.dispose(); // release the running jar's file handle for temp-dir cleanup
    assertTrue(settle(failed, 5000), "回滚退录的失败代码必须可被 GC 回收（类加载器不泄漏）");
  }

  @Test
  @DisplayName("T68 reload 的未知 id 守卫：两/三参重载在任何代码装载前拒绝且不留状态")
  void reloadRejectsUnknownIdsBeforeLoadingCode() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    Path v1 = jar("v1.jar", greetingPlugin("GreetingPlugin", "v1"));

    assertThrows(
        IllegalStateException.class, () -> hrl.reload("missing", v1), "未知 id 的二参 reload 必须拒绝");
    assertThrows(
        IllegalStateException.class,
        () -> hrl.reload("missing", v1, "p1.GreetingPlugin"),
        "未知 id 的三参 reload 必须拒绝");
    assertDoesNotThrow(() -> hrl.load("missing", v1), "守卫不得登记任何状态：同 id 仍可正常装载");
    hrl.dispose();
  }
}
