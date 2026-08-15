/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.hmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * T26: bytecode-level hot module replacement (paper Section 5.2.2, stage 1 of the evaluation):
 * plugin jars load into their own class loaders, a reload swaps the fiber for a fresh instantiation
 * and the replaced code becomes garbage-collectable, a failed reload keeps the previous set
 * running, and code leaked by a live thread stays reachable until that thread ends.
 */
class HotReloadTest {

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

  private static String sleeperPlugin() {
    return "package p1;\n"
        + "public class SleeperPlugin implements io.cordis4j.core.Plugin {\n"
        + "  public io.cordis4j.core.Disposable apply(io.cordis4j.core.Context ctx) {\n"
        + "    Thread t = new Thread(() -> { try { Thread.sleep(4000); } catch (InterruptedException e) { } });\n"
        + "    t.start();\n"
        + "    ctx.provide(\"on\");\n"
        + "    return io.cordis4j.core.Disposables.none();\n"
        + "  }\n"
        + "}\n";
  }

  private static String brokenApplyPlugin() {
    return "package p1;\n"
        + "public class BrokenApplyPlugin implements io.cordis4j.core.Plugin {\n"
        + "  public io.cordis4j.core.Disposable apply(io.cordis4j.core.Context ctx) {\n"
        + "    throw new RuntimeException(\"boom\");\n"
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
  @DisplayName("T26 装载生效；卸载撤绑定并释放代码（类可被 GC 回收）")
  void loadUnloadCollectsCode() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    Path v1 = jar("v1.jar", greetingPlugin("GreetingPlugin", "v1"));

    PluginHandle handle = hrl.load("greeting", v1);
    assertEquals("v1", ctx.get(String.class), "插件装载后必须生效");
    assertEquals(List.of("greeting"), hrl.ids());

    hrl.unload("greeting");
    assertTrue(ctx.find(String.class).isEmpty(), "卸载必须撤回绑定");
    assertTrue(hrl.ids().isEmpty());
    assertThrows(IllegalStateException.class, handle::plugin, "卸载后句柄必须 detach");
    assertTrue(settle(handle, 5000), "释放引用后插件代码必须可被 GC 回收");
  }

  @Test
  @DisplayName("T26 重载换新 fiber；旧代码被回收；旧句柄 detach")
  void reloadSwapsFibersAndCollectsOldCode() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    PluginHandle v1 = hrl.load("greeting", jar("v1.jar", greetingPlugin("GreetingPlugin", "v1")));
    assertEquals("v1", ctx.get(String.class));

    PluginHandle v2 = hrl.reload("greeting", jar("v2.jar", greetingPlugin("GreetingPlugin", "v2")));
    assertEquals("v2", ctx.get(String.class), "重载后必须换成新实例的绑定");
    assertThrows(IllegalStateException.class, v1::plugin, "被替换的句柄必须 detach");
    assertTrue(settle(v1, 5000), "被替换的插件代码必须可被 GC 回收");
    assertFalse(v2.collected(), "新代码仍在运行，不得被回收");
    hrl.dispose(); // release the running jar's file handle for temp-dir cleanup
  }

  @Test
  @DisplayName("T26 失败重载保持旧集合运行（坏 jar 与 apply 失败均回滚）")
  void failedReloadKeepsRunningSet() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    hrl.load("greeting", jar("v1.jar", greetingPlugin("GreetingPlugin", "v1")));
    assertEquals("v1", ctx.get(String.class));

    Path garbage = TestJars.writeGarbage(dir, "broken.jar");
    assertThrows(
        Cordis4jPluginException.class, () -> hrl.reload("greeting", garbage), "坏 jar 必须使重载失败");
    assertEquals("v1", ctx.get(String.class), "失败后旧绑定必须仍在运行");

    Path brokenApply =
        TestJars.compileJar(
            dir,
            "broken-apply.jar",
            List.of(new TestJars.Source("p1.BrokenApplyPlugin", brokenApplyPlugin())));
    assertThrows(
        RuntimeException.class,
        () -> hrl.reload("greeting", brokenApply, "p1.BrokenApplyPlugin"),
        "apply 失败原样传播（核心契约 6.7：plugin 失败保持传播）");
    assertEquals("v1", ctx.get(String.class), "事务回滚后旧集合必须仍然有效");

    PluginHandle v3 = hrl.reload("greeting", jar("v3.jar", greetingPlugin("GreetingPlugin", "v3")));
    assertEquals("v3", ctx.get(String.class), "回滚后的系统必须仍可正常重载");
    assertEquals(v3, hrl.handle("greeting").orElseThrow());
    hrl.dispose();
  }

  @Test
  @DisplayName("T26 泄漏纪律：存活线程阻止代码回收；线程终止后代码可回收")
  void leakedThreadBlocksCollectionUntilItEnds() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    PluginHandle handle =
        hrl.load(
            "sleeper",
            TestJars.compileJar(
                dir,
                "sleeper.jar",
                List.of(new TestJars.Source("p1.SleeperPlugin", sleeperPlugin()))));
    assertEquals("on", ctx.get(String.class));

    hrl.unload("sleeper");
    assertFalse(settle(handle, 1000), "线程存活期间其栈帧持有插件类，代码不得被回收");

    Thread.sleep(3500); // the leaked thread's sleep ends
    assertTrue(settle(handle, 5000), "线程终止后代码必须可被回收");
  }

  @Test
  @DisplayName("T26 多个实现必须显式指定主类；零实现报错")
  void scanAmbiguityRequiresExplicitMainClass() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    Path two =
        TestJars.compileJar(
            dir,
            "two.jar",
            List.of(
                new TestJars.Source("p1.FirstPlugin", greetingPlugin("FirstPlugin", "first")),
                new TestJars.Source("p1.SecondPlugin", greetingPlugin("SecondPlugin", "second"))));

    IllegalArgumentException ambiguity =
        assertThrows(IllegalArgumentException.class, () -> hrl.load("greeting", two), "两个实现必须拒绝扫描");
    assertTrue(ambiguity.getMessage().contains("main class"), () -> ambiguity.getMessage());

    PluginHandle handle = hrl.load("greeting", two, "p1.FirstPlugin");
    assertEquals("first", ctx.get(String.class), "显式主类必须生效");
    assertEquals("p1.FirstPlugin", handle.plugin().getClass().getName());

    Path none =
        TestJars.compileJar(
            dir,
            "none.jar",
            List.of(new TestJars.Source("p1.Plain", "package p1; public class Plain {}")));
    assertThrows(IllegalArgumentException.class, () -> hrl.load("other", none), "零实现必须拒绝");
    hrl.dispose();
  }

  @Test
  @DisplayName("T26 无参 reload 从记录的 jar 重新装载（替换后旧 jar 锁已释放）")
  void reloadFromRecordedJar() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    Path jarPath = jar("same.jar", greetingPlugin("GreetingPlugin", "v1"));
    PluginHandle first = hrl.load("greeting", jarPath);
    assertEquals("v1", ctx.get(String.class));

    PluginHandle second = hrl.reload("greeting");
    assertEquals("v1", ctx.get(String.class), "同 jar 重载内容不变");
    assertTrue(second != first, "重载必须产生新实例");
    assertThrows(IllegalStateException.class, first::plugin);
    assertTrue(settle(first, 5000), "替换后旧代码必须可回收");
    hrl.dispose();
  }

  @Test
  @DisplayName("T26 首次装载失败：不留下任何痕迹（ids/handle 为空），可再次装载")
  void failedInitialLoadCleansUp() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    Path garbage = TestJars.writeGarbage(dir, "garbage.jar");

    assertThrows(Cordis4jPluginException.class, () -> hrl.load("greeting", garbage));
    assertTrue(hrl.ids().isEmpty(), "失败的首次装载不得留下 id");
    assertTrue(hrl.handle("greeting").isEmpty(), "失败的首次装载不得留下句柄");
    assertTrue(ctx.find(String.class).isEmpty());

    PluginHandle handle =
        hrl.load("greeting", jar("v1.jar", greetingPlugin("GreetingPlugin", "v1")));
    assertEquals("v1", ctx.get(String.class), "失败后可正常再装载");
    hrl.dispose();
    assertTrue(settle(handle, 5000));
  }

  @Test
  @DisplayName("T26 dispose 后一切操作拒绝；未知 id 的 reload/unload 拒绝")
  void disposeRejectsFurtherOperations() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    Path v1 = jar("v1.jar", greetingPlugin("GreetingPlugin", "v1"));
    hrl.load("greeting", v1);

    assertThrows(IllegalStateException.class, () -> hrl.reload("missing"), "未知 id 必须拒绝");
    assertThrows(IllegalStateException.class, () -> hrl.unload("missing"), "未知 id 必须拒绝");
    assertThrows(
        IllegalArgumentException.class, () -> hrl.load("greeting", v1), "已装载 id 必须拒绝重复 load");

    hrl.dispose();
    assertThrows(IllegalStateException.class, () -> hrl.load("x", v1), "dispose 后 load 必须拒绝");
    assertThrows(
        IllegalStateException.class, () -> hrl.reload("greeting"), "dispose 后 reload 必须拒绝");
    assertThrows(
        IllegalStateException.class, () -> hrl.unload("greeting"), "dispose 后 unload 必须拒绝");
    assertThrows(NullPointerException.class, () -> hrl.load(null, v1));
    assertThrows(NullPointerException.class, () -> hrl.reload(null));
    assertThrows(NullPointerException.class, () -> hrl.unload(null));
  }

  @Test
  @DisplayName("T26 dispose 卸载全部组件并释放全部代码；幂等")
  void disposeUnloadsEverything() throws Exception {
    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    PluginHandle a = hrl.load("a", jar("a.jar", greetingPlugin("GreetingPlugin", "a")));
    PluginHandle b =
        hrl.load(
            "b",
            TestJars.compileJar(
                dir, "b.jar", List.of(new TestJars.Source("p1.NumberPlugin", numberPlugin(42)))));
    assertEquals("a", ctx.get(String.class));
    assertEquals(42L, ctx.get(Long.class), "第二个插件提供独立键");

    hrl.dispose();
    assertTrue(ctx.find(String.class).isEmpty(), "dispose 必须卸载全部组件");
    assertTrue(ctx.find(Long.class).isEmpty());
    assertTrue(hrl.ids().isEmpty());
    hrl.dispose(); // idempotent
    assertThrows(
        IllegalStateException.class,
        () -> hrl.load("c", jar("c.jar", greetingPlugin("GreetingPlugin", "c"))));
    assertTrue(settle(a, 5000) && settle(b, 5000), "dispose 后全部插件代码必须可回收");
  }
}
