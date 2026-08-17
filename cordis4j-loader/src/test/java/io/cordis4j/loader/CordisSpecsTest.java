/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cordis4j.core.ComponentSpec;
import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.CordisException;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.Plugin;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T45: the mapping onto the core's composition (decision D28): the isolation table flattens into
 * nested {@code ComponentSpec.Isolate} realms in table order ({@code true} is a local realm, a
 * label a shared one), groups pass through, disabled entries drop out of the mount while their
 * metadata survives, per-entry config/inject/intercept reach the host through {@code EntryMeta},
 * and layered composition composes with later layers winning.
 */
class CordisSpecsTest {

  /** A stand-in component: the mapping never applies plugins, it only places them. */
  record Stub(String label) implements Plugin {
    @Override
    public Disposable apply(Context ctx) {
      return Disposables.none();
    }
  }

  private static final Stub FOO = new Stub("foo");
  private static final Stub BAR = new Stub("bar");

  private static ComponentResolver resolver() {
    Map<String, Class<?>> services = new LinkedHashMap<>();
    services.put("config", String.class);
    services.put("logger", List.class);
    return ComponentResolvers.builtins(Map.of("foo", FOO, "bar", BAR), services);
  }

  private static ComponentSpec.Entry expectEntry(ComponentSpec spec) {
    return assertInstanceOf(ComponentSpec.Entry.class, spec);
  }

  private static ComponentSpec.Isolate expectIsolate(ComponentSpec spec) {
    return assertInstanceOf(ComponentSpec.Isolate.class, spec);
  }

  @Test
  @DisplayName("T45 普通条目映射为 Entry，解析器收到 name 与 baseUrl，元数据按 id 索引")
  void mapsPlainEntries() {
    Path base = Path.of("app");
    List<String> names = new ArrayList<>();
    List<Path> bases = new ArrayList<>();
    ComponentResolver recording =
        new ComponentResolver() {
          @Override
          public Plugin resolve(String name, Path baseUrl) {
            names.add(name);
            bases.add(baseUrl);
            return FOO;
          }
        };

    CordisSpecs.Mapping mapping =
        CordisSpecs.toSpecs(List.of(CordisEntry.of("a", "foo", Map.of("v", 1))), base, recording);

    assertEquals(1, mapping.specs().size());
    ComponentSpec.Entry entry = expectEntry(mapping.specs().get(0));
    assertEquals("a", entry.id());
    assertEquals(FOO, entry.component());
    assertEquals(List.of("foo"), names, "组件名交由宿主解析");
    assertEquals(List.of(base), bases, "baseUrl 原样传递");
    CordisSpecs.EntryMeta meta = mapping.meta().get("a");
    assertEquals("foo", meta.name());
    assertEquals(Map.of("v", 1), meta.config(), "条目配置树进入元数据供宿主应用");
  }

  @Test
  @DisplayName("T45 group 映射为 Group spec 且不参与组件解析")
  void mapsGroups() {
    CordisEntry child = CordisEntry.of("c1", "foo", null);
    CordisEntry group = CordisEntry.group("g", List.of(child));

    CordisSpecs.Mapping mapping = CordisSpecs.toSpecs(List.of(group), Path.of("."), resolver());

    ComponentSpec.Group spec = assertInstanceOf(ComponentSpec.Group.class, mapping.specs().get(0));
    assertEquals("g", spec.id());
    assertEquals("c1", expectEntry(spec.children().get(0)).id());
    assertTrue(!mapping.meta().containsKey("g"), "group 本身无元数据（不装载组件）");
  }

  @Test
  @DisplayName("T45 isolate 表展平：true → 本地域 '#<entryId>'，label → 共享域 '@<label>'")
  void flattensIsolationTable() {
    CordisEntry local =
        new CordisEntry("e1", "foo", null, false, false, null, null, Map.of("config", true), null);
    CordisEntry shared =
        new CordisEntry(
            "e2", "foo", null, false, false, null, null, Map.of("config", "edge"), null);

    CordisSpecs.Mapping mapping =
        CordisSpecs.toSpecs(List.of(local, shared), Path.of("."), resolver());

    ComponentSpec.Isolate localRealm = expectIsolate(mapping.specs().get(0));
    assertEquals(String.class, localRealm.type(), "服务名由宿主解析为 JVM 类型");
    assertEquals("#e1", localRealm.realm(), "true 是条目独占的本地域");
    assertEquals("e1", expectEntry(localRealm.children().get(0)).id());

    ComponentSpec.Isolate sharedRealm = expectIsolate(mapping.specs().get(1));
    assertEquals("@edge", sharedRealm.realm(), "label 是跨条目共享的全局域");
  }

  @Test
  @DisplayName("T45 多服务 isolate 按表顺序由外向内嵌套")
  void nestsIsolationInTableOrder() {
    Map<String, Object> table = new LinkedHashMap<>();
    table.put("config", true);
    table.put("logger", "L");
    CordisEntry entry = new CordisEntry("e1", "foo", null, false, false, null, null, table, null);

    CordisSpecs.Mapping mapping = CordisSpecs.toSpecs(List.of(entry), Path.of("."), resolver());

    ComponentSpec.Isolate outer = expectIsolate(mapping.specs().get(0));
    assertEquals(String.class, outer.type(), "表首服务是最外层");
    ComponentSpec.Isolate inner = expectIsolate(outer.children().get(0));
    assertEquals(List.class, inner.type());
    assertEquals("@L", inner.realm());
    assertEquals("e1", expectEntry(inner.children().get(0)).id(), "条目位于最内层");
  }

  @Test
  @DisplayName("T45 disabled 条目退出装载但保留元数据；group 的 disabled 沿链继承")
  void dropsDisabledEntries() {
    CordisEntry disabledTop =
        new CordisEntry("off", "foo", "topCfg", false, true, null, null, null, null);
    CordisEntry disabledChild = CordisEntry.of("c1", "foo", "cfg1");
    CordisEntry enabledChild = CordisEntry.of("c2", "bar", null);
    CordisEntry disabledGroup =
        new CordisEntry(
            "g1",
            "@group",
            List.of(disabledChild, enabledChild),
            true,
            true,
            null,
            null,
            null,
            null);

    CordisSpecs.Mapping mapping =
        CordisSpecs.toSpecs(
            List.of(disabledTop, disabledGroup, CordisEntry.of("e1", "foo", null)),
            Path.of("."),
            resolver());

    assertEquals(2, mapping.specs().size(), "仅禁用组与启用条目留在装载集");
    ComponentSpec.Group group = assertInstanceOf(ComponentSpec.Group.class, mapping.specs().get(0));
    assertTrue(group.children().isEmpty(), "group 自身保留，禁用链剔除其全部子条目");
    assertEquals("e1", expectEntry(mapping.specs().get(1)).id(), "未禁用条目正常装载");
    assertEquals("topCfg", mapping.meta().get("off").config(), "顶层禁用条目元数据保留");
    assertEquals("cfg1", mapping.meta().get("g1:c1").config(), "被剔除子条目的元数据仍在（含配置树，键为展平 id）");
    assertEquals("bar", mapping.meta().get("g1:c2").name(), "禁用组内条目元数据完整（无论自身开关）");
  }

  @Test
  @DisplayName("T45 inject 与 intercept 逐字透传到 EntryMeta")
  void passesInjectAndInterceptThrough() {
    CordisEntry entry =
        new CordisEntry(
            "a",
            "foo",
            null,
            false,
            false,
            List.of("logger"),
            Map.of("cache", Map.of("level", 2)),
            Map.of(),
            Map.of());

    CordisSpecs.Mapping mapping = CordisSpecs.toSpecs(List.of(entry), Path.of("."), resolver());

    CordisSpecs.EntryMeta meta = mapping.meta().get("a");
    assertEquals(List.of("logger"), meta.inject(), "依赖声明逐字保留（类型化是宿主知识）");
    assertEquals(Map.of("cache", Map.of("level", 2)), meta.intercept());
  }

  @Test
  @DisplayName("T45 缺 id 的条目拒绝映射（不稳定装载）；未知组件名由解析器拒绝")
  void rejectsUnstableOrUnknownEntries() {
    CordisException noId =
        assertThrows(
            CordisException.class,
            () ->
                CordisSpecs.toSpecs(
                    List.of(
                        new CordisEntry(null, "foo", null, false, false, null, null, null, null)),
                    Path.of("."),
                    resolver()));
    assertTrue(noId.getMessage().contains("id"), "无 id 条目必须拒绝");

    CordisException unknown =
        assertThrows(
            UnknownComponentException.class,
            () ->
                CordisSpecs.toSpecs(
                    List.of(CordisEntry.of("a", "nope", null)), Path.of("."), resolver()));
    assertTrue(unknown.getMessage().contains("nope"), "未知名报错指明组件");
    assertTrue(unknown instanceof CordisException, "解析失败是 Cordis 异常族");

    CordisException unknownService =
        assertThrows(
            UnknownComponentException.class,
            () ->
                CordisSpecs.toSpecs(
                    List.of(
                        new CordisEntry(
                            "a",
                            "foo",
                            null,
                            false,
                            false,
                            null,
                            null,
                            Map.of("ghost", true),
                            null)),
                    Path.of("."),
                    resolver()));
    assertTrue(unknownService.getMessage().contains("ghost"), "isolate 的服务名同样要能解析");
  }

  @Test
  @DisplayName("T45 builtins 解析器：组件表与服务表分派，未知名拒绝；单参重载拒绝服务名")
  void builtinsResolverDispatches() {
    ComponentResolver resolver = ComponentResolvers.builtins(Map.of("foo", FOO));
    assertEquals(FOO, resolver.resolve("foo", Path.of(".")), "表中名字命中稳定实例");
    assertThrows(UnknownComponentException.class, () -> resolver.resolve("bar", Path.of(".")));
    assertThrows(UnknownComponentException.class, () -> resolver.serviceType("config"));
    assertEquals(String.class, resolver().serviceType("config"), "双参重载带服务表");
  }

  @Test
  @DisplayName("T45 层叠合成：bundle 层按序叠加，后层覆盖先层（端到端）")
  void layeredCompositionLaterWins() {
    CordisEntry base = CordisEntry.of("a", "foo", "v1");
    List<CordisEntry> tree = List.of(base);

    tree = Patches.apply(tree, List.of(Patch.override("a", "v2"))); // bundle layer 1
    tree = Patches.apply(tree, List.of(Patch.override("a", "v3"))); // bundle layer 2

    CordisSpecs.Mapping mapping = CordisSpecs.toSpecs(tree, Path.of("."), resolver());

    assertEquals("v3", mapping.meta().get("a").config(), "后层 patch 胜出");
    assertEquals(1, mapping.specs().size());
    assertEquals("a", expectEntry(mapping.specs().get(0)).id());
  }

  @Test
  @DisplayName("T45 空参校验与畸形 group 子列表")
  void requireNonNullArguments() {
    Path base = Path.of(".");
    assertThrows(NullPointerException.class, () -> CordisSpecs.toSpecs(null, base, resolver()));
    assertThrows(
        NullPointerException.class, () -> CordisSpecs.toSpecs(List.of(), null, resolver()));
    assertThrows(NullPointerException.class, () -> CordisSpecs.toSpecs(List.of(), base, null));
    assertThrows(
        NullPointerException.class,
        () -> CordisSpecs.toSpecs(List.of((CordisEntry) null), base, resolver()));
    CordisEntry malformed =
        new CordisEntry(
            "g", "@group", Map.of("not", "entries"), true, false, null, null, null, null);
    CordisException broken =
        assertThrows(
            CordisException.class, () -> CordisSpecs.toSpecs(List.of(malformed), base, resolver()));
    assertTrue(broken.getMessage().contains("must be a list"), "group 的 config 必须是条目列表");
  }

  @Test
  @DisplayName("T54 group 的 isolate 沿原型链继承：子条目无表用父 label，子条目 true 覆盖为本地域")
  void inheritsGroupIsolationDownTheChain() {
    CordisEntry plainChild = CordisEntry.of("c1", "foo", null);
    CordisEntry overridingChild =
        new CordisEntry("c2", "foo", null, false, false, null, null, Map.of("config", true), null);
    CordisEntry group =
        new CordisEntry(
            "g",
            "@group",
            List.of(plainChild, overridingChild),
            true,
            false,
            null,
            null,
            Map.of("config", "shared"),
            null);

    CordisSpecs.Mapping mapping = CordisSpecs.toSpecs(List.of(group), Path.of("."), resolver());

    ComponentSpec.Group mounted =
        assertInstanceOf(ComponentSpec.Group.class, mapping.specs().get(0));
    // plain child: the inherited label wraps it into the shared realm
    ComponentSpec.Isolate inherited = expectIsolate(mounted.children().get(0));
    assertEquals("@shared", inherited.realm(), "无自身表的子条目必须继承 group 的 isolate label");
    assertEquals(String.class, inherited.type());
    // overriding child: its own true wins over the inherited label - a local realm
    ComponentSpec.Isolate local = expectIsolate(mounted.children().get(1));
    assertEquals("#c2", local.realm(), "子条目自身的 true 必须覆盖继承的 label（本地域）");
  }

  @Test
  @DisplayName("T54 group 的 intercept 继承合并进 EntryMeta：子覆盖同键，异键并存")
  void mergesGroupInterceptIntoEntryMeta() {
    CordisEntry plainChild = CordisEntry.of("c1", "foo", null);
    CordisEntry overridingChild =
        new CordisEntry(
            "c2",
            "foo",
            null,
            false,
            false,
            null,
            Map.of("logger", Map.of("level", 9)),
            null,
            null);
    CordisEntry group =
        new CordisEntry(
            "g",
            "@group",
            List.of(plainChild, overridingChild),
            true,
            false,
            null,
            Map.of("logger", Map.of("level", 1), "cache", Map.of("ttl", 5)),
            null,
            null);

    CordisSpecs.Mapping mapping = CordisSpecs.toSpecs(List.of(group), Path.of("."), resolver());

    assertEquals(
        Map.of("logger", Map.of("level", 1), "cache", Map.of("ttl", 5)),
        mapping.meta().get("g:c1").intercept(),
        "无自身表的子条目必须完整继承 group 的 intercept");
    assertEquals(
        Map.of("logger", Map.of("level", 9), "cache", Map.of("ttl", 5)),
        mapping.meta().get("g:c2").intercept(),
        "子条目同键覆盖父表、异键保留（原型链合并）");
  }

  @Test
  @DisplayName("T54 isolate 表 falsy 值不产生域；非字符串非布尔值 fail-fast")
  void skipsFalsyIsolationLabels() {
    for (Object falsy : new Object[] {null, Boolean.FALSE, ""}) {
      Map<String, Object> table = new LinkedHashMap<>();
      table.put("config", falsy); // Map.of rejects nulls; the YAML reader can produce them
      CordisEntry entry = new CordisEntry("a", "foo", null, false, false, null, null, table, null);
      CordisSpecs.Mapping mapping = CordisSpecs.toSpecs(List.of(entry), Path.of("."), resolver());
      ComponentSpec.Entry mounted = expectEntry(mapping.specs().get(0));
      assertEquals("a", mounted.id(), "falsy label（" + falsy + "）不得产生任何 Isolate 包裹（直接是 Entry）");
    }

    CordisEntry malformed =
        new CordisEntry("b", "foo", null, false, false, null, null, Map.of("config", 42), null);
    CordisException broken =
        assertThrows(
            CordisException.class,
            () -> CordisSpecs.toSpecs(List.of(malformed), Path.of("."), resolver()));
    assertTrue(
        broken.getMessage().contains("isolate label"),
        "非字符串非布尔的 label 必须以 CordisException fail-fast");
  }

  @Test
  @DisplayName("T56 meta 键为展平 id（group 前缀）；同层重复展平 id fail-fast；端到端 join")
  void metaKeysAreFlattenedIds() throws Exception {
    CordisEntry child = CordisEntry.of("c1", "foo", "cfg");
    CordisEntry group = CordisEntry.group("g", List.of(child));

    CordisSpecs.Mapping mapping = CordisSpecs.toSpecs(List.of(group), Path.of("."), resolver());
    assertEquals("g:c1", mapping.meta().get("g:c1").id(), "meta 键与 EntryMeta.id 必须是展平 id");

    CordisEntry duplicate = CordisEntry.of("c1", "bar", null);
    CordisEntry ambiguous = CordisEntry.group("g", List.of(child, duplicate));
    CordisException collision =
        assertThrows(
            CordisException.class,
            () -> CordisSpecs.toSpecs(List.of(ambiguous), Path.of("."), resolver()));
    assertTrue(
        collision.getMessage().contains("duplicate flattened entry id: g:c1"),
        "同 group 内重复 id 展平后冲突必须 fail-fast");

    // End-to-end join: reconcile the mapped tree (the engine's flattened id for the mounted
    // child is "g:c1"), then find metadata by exactly that id.
    io.cordis4j.core.Context ctx = Contexts.create();
    io.cordis4j.core.Loader loader = io.cordis4j.core.Loader.of(ctx);
    CordisSpecs.Mapping joined = CordisSpecs.toSpecs(List.of(group), Path.of("."), resolver());
    loader.reconcileTree(joined.specs());
    assertTrue(joined.meta().containsKey("g:c1"), "展平 id 必须能直接查到 meta（端到端 join）");
    assertFalse(joined.meta().containsKey("c1"), "裸 id 不得再作为 meta 键（旧语义）");
    assertEquals("cfg", joined.meta().get("g:c1").config());
    ctx.dispose();
  }
}
