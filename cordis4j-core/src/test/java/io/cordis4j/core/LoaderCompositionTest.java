/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T33: the loader composition DSL (decision D26, the JVM form of upstream's
 * entry/group/isolate/tree configuration): groups prefix their children, isolation realms load into
 * derived contexts, includes inline another source against the base directory, and the flattened
 * set reconciles transactionally through the D18 engine.
 *
 * <p>T39: realm reuse across reconciles. T53: the realm key is the isolate-chain path - groups do
 * not constitute isolation boundaries (the same label under different groups of one root is one
 * shared realm), while a nested inner realm never merges with a top-level one carrying the same
 * label.
 */
class LoaderCompositionTest {

  record Plugin(String id, String qualifier, String value) implements io.cordis4j.core.Plugin {
    @Override
    public Disposable apply(Context ctx) {
      ctx.provide(ServiceKey.of(String.class, qualifier), value);
      return Disposables.none();
    }
  }

  static Plugin provider(String id, String qualifier, String value) {
    return new Plugin(id, qualifier, value);
  }

  static class BrokenPlugin implements io.cordis4j.core.Plugin {
    @Override
    public Disposable apply(Context c) {
      throw new RuntimeException("boom");
    }
  }

  /** Marker service type for nested-isolate scenarios. */
  static class Clock {}

  @Test
  @DisplayName("T33 group 前缀展平子条目 id（':' 分隔）；顶层条目不变")
  void groupsPrefixTheirChildren() {
    Context ctx = Contexts.create();
    Loader loader = Loader.of(ctx);
    loader.reconcileTree(
        List.of(
            new ComponentSpec.Group(
                "g",
                List.of(
                    new ComponentSpec.Entry("a", provider("a", "g.a", "ga")),
                    new ComponentSpec.Entry("b", provider("b", "g.b", "gb")))),
            new ComponentSpec.Entry("top", provider("top", "top", "t"))));

    assertEquals("ga", ctx.get(ServiceKey.of(String.class, "g.a")), "g:a 必须装载");
    assertEquals("gb", ctx.get(ServiceKey.of(String.class, "g.b")));
    assertEquals("t", ctx.get(ServiceKey.of(String.class, "top")));
  }

  @Test
  @DisplayName("T33 isolate 域派生：同 qualifier 键在兄弟 realm 中共存（无供给冲突）")
  void isolateRealmsCoexist() {
    Context ctx = Contexts.create();
    Loader loader = Loader.of(ctx);
    loader.reconcileTree(
        List.of(
            new ComponentSpec.Isolate(
                String.class,
                "ra",
                List.of(new ComponentSpec.Entry("a1", provider("a1", "shared", "A")))),
            new ComponentSpec.Isolate(
                String.class,
                "rb",
                List.of(new ComponentSpec.Entry("b1", provider("b1", "shared", "B"))))));

    // no exception proves the realms redirected the store keys; now unload one realm
    loader.reconcileTree(
        List.of(
            new ComponentSpec.Isolate(
                String.class,
                "rb",
                List.of(new ComponentSpec.Entry("b1", provider("b1", "shared", "B"))))));
    // and rebuild it: the realm was disposed and is recreated cleanly
    loader.reconcileTree(
        List.of(
            new ComponentSpec.Isolate(
                String.class,
                "rb",
                List.of(new ComponentSpec.Entry("b1", provider("b1", "shared", "B"))))));
  }

  @Test
  @DisplayName("T33 include 引用以 baseUrl 相对解析并内联条目")
  void includesInlineWithBaseUrl() {
    Context ctx = Contexts.create();
    Loader loader = Loader.of(ctx);
    List<Path> received = new ArrayList<>();
    Path baseDir = Path.of("config");
    loader.reconcileTree(
        baseDir,
        List.of(
            new ComponentSpec.Include(
                Path.of("extra.list"),
                file -> {
                  received.add(file);
                  return List.of(new ComponentSpec.Entry("extra", provider("extra", "extra", "e")));
                })));

    assertEquals(List.of(Path.of("config", "extra.list")), received, "resolver 必须收到绝对路径");
    assertEquals("e", ctx.get(ServiceKey.of(String.class, "extra")), "内联条目必须装载");
  }

  @Test
  @DisplayName("T33 树形 reconcile 事务回滚：失败的组件恢复旧集合")
  void treeReconcileRollsBack() {
    Context ctx = Contexts.create();
    Loader loader = Loader.of(ctx);
    loader.reconcileTree(List.of(new ComponentSpec.Entry("ok", provider("ok", "ok", "v1"))));
    assertEquals("v1", ctx.get(ServiceKey.of(String.class, "ok")));

    assertThrows(
        RuntimeException.class,
        () ->
            loader.reconcileTree(
                List.of(
                    new ComponentSpec.Entry("ok", provider("ok", "ok", "v2")),
                    new ComponentSpec.Entry("bad", new BrokenPlugin()))));
    assertEquals("v1", ctx.get(ServiceKey.of(String.class, "ok")), "回滚后旧集合必须仍有效");
  }

  @Test
  @DisplayName("T33 展平重复 id 立即失败（fail-fast）且旧集合不动")
  void duplicateIdsFailFast() {
    Context ctx = Contexts.create();
    Loader loader = Loader.of(ctx);
    loader.reconcileTree(List.of(new ComponentSpec.Entry("g:a", provider("top", "g:a", "t"))));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            loader.reconcileTree(
                List.of(
                    new ComponentSpec.Group(
                        "g", List.of(new ComponentSpec.Entry("a", provider("a", "x", "x")))),
                    new ComponentSpec.Entry("g:a", provider("dup", "g:a", "dup")))),
        "重复 id 必须拒绝");
    assertEquals("t", ctx.get(ServiceKey.of(String.class, "g:a")), "失败后旧集合必须不动");
  }

  @Test
  @DisplayName("T33 Context.withBaseUrl 派生；baseUrl 沿树继承；dispose 后访问拒绝")
  void baseUrlDerivation() {
    Context ctx = Contexts.create();
    assertTrue(ctx.baseUrl().isEmpty(), "未设置时必须为空");
    assertThrows(NullPointerException.class, () -> ctx.withBaseUrl(null));

    Context configured = ctx.withBaseUrl(Path.of("config"));
    assertEquals(Path.of("config"), configured.baseUrl().orElseThrow());
    assertEquals(Path.of("config"), configured.fork().baseUrl().orElseThrow(), "子孙必须继承");
    assertEquals(Path.of("config"), configured.baseUrl().orElseThrow());

    ctx.dispose();
    assertThrows(IllegalStateException.class, ctx::baseUrl, "dispose 后必须拒绝");
  }

  @Test
  @DisplayName("T39 隔离域跨 reconcile 复用：同实例条目不重载，realm 变化才重载")
  void isolateDomainsReuseAcrossReconciles() {
    Context ctx = Contexts.create();
    Loader loader = Loader.of(ctx);
    java.util.concurrent.atomic.AtomicInteger activations =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger siblingLoads =
        new java.util.concurrent.atomic.AtomicInteger();
    io.cordis4j.core.Plugin counted =
        c -> {
          activations.incrementAndGet();
          return Disposables.none();
        };
    List<ComponentSpec> tree =
        List.of(
            new ComponentSpec.Isolate(
                String.class, "r1", List.of(new ComponentSpec.Entry("a", counted))));

    loader.reconcileTree(tree);
    loader.reconcileTree(tree); // same instance at the same realm position: must not reload
    assertEquals(1, activations.get(), "同实例 + 同 realm 位置的二次 reconcile 不得重载（修复前恒重载）");

    loader.reconcileTree(
        List.of(
            new ComponentSpec.Isolate(
                String.class, "r2", List.of(new ComponentSpec.Entry("a", counted)))));
    assertEquals(2, activations.get(), "realm 变化（重写键变）必须重载");

    loader.reconcileTree(
        List.of(
            new ComponentSpec.Isolate(
                String.class,
                "r2",
                List.of(
                    new ComponentSpec.Entry("a", counted),
                    new ComponentSpec.Entry(
                        "b",
                        c -> {
                          siblingLoads.incrementAndGet();
                          return Disposables.none();
                        })))));
    assertEquals(2, activations.get(), "仅新增兄弟条目不得重载同 realm 内的既有条目");
    assertEquals(1, siblingLoads.get(), "新增条目必须装载");
  }

  @Test
  @DisplayName("T53 不同 group 内同 label 的 isolate 共享一个域（group 不构成隔离边界）")
  void sameLabelUnderDifferentGroupsSharesOneRealm() {
    Context ctx = Contexts.create();
    Loader loader = Loader.of(ctx);
    java.util.concurrent.atomic.AtomicReference<String> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    ServiceKey<String> key = ServiceKey.of(String.class, "db");
    List<ComponentSpec> tree =
        List.of(
            new ComponentSpec.Group(
                "g1",
                List.of(
                    new ComponentSpec.Isolate(
                        String.class,
                        "shared",
                        List.of(
                            new ComponentSpec.Entry(
                                "p",
                                c -> {
                                  c.provide(key, "from-g1");
                                  return Disposables.none();
                                }))))),
            new ComponentSpec.Group(
                "g2",
                List.of(
                    new ComponentSpec.Isolate(
                        String.class,
                        "shared",
                        List.of(
                            new ComponentSpec.Entry(
                                "d",
                                c -> {
                                  seen.set(c.find(key).orElse(null));
                                  return Disposables.none();
                                }))))));

    loader.reconcileTree(tree);

    assertEquals(
        "from-g1", seen.get(), "同根下同 label 的 isolate 必须共享域：g2 的条目必须看到 g1 域内提供的绑定（GlobalRealm）");
  }

  @Test
  @DisplayName("T53 嵌套内层域不与同 label 顶层域合并（域键含完整 Isolate 链）")
  void nestedRealmNeverMergesWithTopLevelSameLabel() {
    Context ctx = Contexts.create();
    Loader loader = Loader.of(ctx);
    java.util.concurrent.atomic.AtomicBoolean leaked =
        new java.util.concurrent.atomic.AtomicBoolean();
    ServiceKey<String> key = ServiceKey.of(String.class, "svc");
    List<ComponentSpec> tree =
        List.of(
            new ComponentSpec.Isolate(
                String.class,
                "outer",
                List.of(
                    new ComponentSpec.Isolate(
                        Clock.class,
                        "L",
                        List.of(
                            new ComponentSpec.Entry(
                                "nested",
                                c -> {
                                  c.provide(key, "nested-value");
                                  return Disposables.none();
                                }))))),
            new ComponentSpec.Isolate(
                Clock.class,
                "L",
                List.of(
                    new ComponentSpec.Entry(
                        "top",
                        c -> {
                          leaked.set(c.find(key).isPresent());
                          return Disposables.none();
                        }))));

    loader.reconcileTree(tree);

    assertFalse(leaked.get(), "嵌套内层域的键必须包含外层链，不得与同 label 的顶层域合并（顶层看不到嵌套域内的绑定）");
  }
}
