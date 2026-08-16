/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cordis4j.core.CordisException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T43: patch layers with upstream include's semantics (decision D28): insertions append to the root
 * or into a located group, overrides locate rows by id anywhere in the tree, a name mismatch skips
 * the patch, {@code config} replaces wholesale, and a later patch in the same layer sees what an
 * earlier one inserted.
 */
class PatchTest {

  private static List<CordisEntry> children(CordisEntry group) {
    @SuppressWarnings("unchecked")
    List<CordisEntry> children = (List<CordisEntry>) group.config();
    return children;
  }

  private static Patch insertInto(String groupId, CordisEntry... rows) {
    return new Patch(groupId, null, List.of(rows), null, null, null, null, null, null, null);
  }

  private static Patch overrideWith(String id, String name, Object config) {
    return new Patch(id, name, null, config, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("T43 无 id 的 insert 追加到根列表；原树不被修改")
  void insertsAtRootWithoutId() {
    CordisEntry existing = CordisEntry.of("a", "foo", Map.of("v", 1));
    List<CordisEntry> tree = List.of(existing);
    CordisEntry added = CordisEntry.of("b", "bar", null);

    List<CordisEntry> patched = Patches.apply(tree, List.of(Patch.insert(added)));

    assertEquals(List.of("a", "b"), patched.stream().map(CordisEntry::id).toList());
    assertEquals(1, tree.size(), "输入树保持不变");
    assertNotSame(tree, patched);
  }

  @Test
  @DisplayName("T43 带 id 的 insert 追加进定位 group 的子列表（含嵌套 group）")
  void insertsIntoLocatedGroups() {
    CordisEntry leaf = CordisEntry.of("g2c", "leaf", null);
    CordisEntry nested = CordisEntry.group("g2", List.of(leaf));
    CordisEntry outer = CordisEntry.group("g1", List.of(nested));
    CordisEntry appended = CordisEntry.of("new", "foo", null);

    List<CordisEntry> patched = Patches.apply(List.of(outer), List.of(insertInto("g2", appended)));

    List<CordisEntry> outerChildren = children(patched.get(0));
    List<CordisEntry> nestedChildren = children(outerChildren.get(0));
    assertEquals(
        List.of("g2c", "new"),
        nestedChildren.stream().map(CordisEntry::id).toList(),
        "新行追加到嵌套 group 末尾");
    assertEquals("g1", patched.get(0).id(), "外层结构保持");
  }

  @Test
  @DisplayName("T43 insert 目标不存在或目标不是 group 均报错")
  void rejectsBrokenInsertionTargets() {
    CordisException missing =
        assertThrows(
            CordisException.class,
            () ->
                Patches.apply(
                    List.of(), List.of(insertInto("nowhere", CordisEntry.of("x", "y", null)))));
    assertTrue(missing.getMessage().contains("not found"), "缺失目标必须报错");

    CordisEntry notAGroup = CordisEntry.of("plain", "foo", null);
    CordisException wrongKind =
        assertThrows(
            CordisException.class,
            () ->
                Patches.apply(
                    List.of(notAGroup),
                    List.of(insertInto("plain", CordisEntry.of("x", "y", null)))));
    assertTrue(wrongKind.getMessage().contains("not a group"), "目标必须是 group");
  }

  @Test
  @DisplayName("T43 按 id 覆盖：config 整体替换不深合并")
  void overridesConfigWholesale() {
    CordisEntry target = CordisEntry.of("a", "foo", Map.of("keep", false, "old", Map.of("x", 1)));
    Patch override = Patch.override("a", Map.of("old", Map.of("y", 2)));

    List<CordisEntry> patched = Patches.apply(List.of(target), List.of(override));

    assertEquals(Map.of("old", Map.of("y", 2)), patched.get(0).config(), "config 整体替换");
    assertTrue(!((Map<?, ?>) patched.get(0).config()).containsKey("keep"), "旧键不残留（无深合并）");
  }

  @Test
  @DisplayName("T43 name 校验不匹配跳过该 patch；匹配时 name 一并更新")
  void validatesNameOnOverride() {
    CordisEntry target = CordisEntry.of("a", "foo", "v1");

    List<CordisEntry> skipped =
        Patches.apply(List.of(target), List.of(overrideWith("a", "other", "v2")));
    assertEquals("v1", skipped.get(0).config(), "name 不匹配时 patch 跳过");

    List<CordisEntry> matched =
        Patches.apply(List.of(target), List.of(overrideWith("a", "foo", "v2")));
    assertEquals("foo", matched.get(0).name(), "name 是匹配守卫，匹配后也不改名");
    assertEquals("v2", matched.get(0).config());
  }

  @Test
  @DisplayName("T43 覆盖递归定位嵌套 group 内的条目；目标不存在仅告警不抛")
  void locatesNestedAndWarnsOnMissing() {
    CordisEntry inner = CordisEntry.of("deep", "foo", "v1");
    CordisEntry group = CordisEntry.group("g", List.of(inner));
    List<CordisEntry> tree = List.of(group, CordisEntry.of("top", "bar", null));

    List<CordisEntry> patched = Patches.apply(tree, List.of(Patch.override("deep", "v2")));
    assertEquals("v2", children(patched.get(0)).get(0).config(), "嵌套条目按 id 定位覆盖");
    assertEquals("top", patched.get(1).id(), "兄弟条目不受影响");

    List<CordisEntry> untouched = Patches.apply(tree, List.of(Patch.override("ghost", "v9")));
    assertEquals(tree, untouched, "缺失目标静默告警，树不变");
  }

  @Test
  @DisplayName("T43 同层后续 patch 可定位先前 insert 的行（upstream patch.spec 场景）")
  void laterPatchesSeeEarlierInserts() {
    CordisEntry inserted = CordisEntry.of("fresh", "foo", "v1");

    List<CordisEntry> patched =
        Patches.apply(
            List.of(),
            List.of(
                Patch.insert(inserted),
                Patch.override("fresh", "v2"),
                insertInto(null, CordisEntry.of("after", "bar", null))));

    assertEquals(
        List.of("fresh", "after"),
        patched.stream().map(CordisEntry::id).toList(),
        "insert 先入树，override 随后命中它");
    assertEquals("v2", patched.get(0).config());
  }

  @Test
  @DisplayName("T43 覆盖 isolate/intercept 与 extras：按键合并，后入覆盖")
  void mergesMapsOnOverride() {
    CordisEntry target =
        new CordisEntry(
            "a",
            "foo",
            null,
            false,
            false,
            null,
            Map.of("cache", "old", "keep", "yes"),
            Map.of("config", true),
            Map.of("extra1", 1));
    Patch patch =
        new Patch(
            "a",
            null,
            null,
            null,
            null,
            true,
            null,
            Map.of("cache", "new"),
            Map.of("logger", "L"),
            Map.of("extra2", 2));

    List<CordisEntry> patched = Patches.apply(List.of(target), List.of(patch));

    CordisEntry result = patched.get(0);
    assertEquals(Map.of("cache", "new", "keep", "yes"), result.intercept(), "同键覆盖、异键保留");
    assertEquals(Map.of("config", true, "logger", "L"), result.isolate(), "isolate 表按键合并");
    assertEquals(Map.of("extra1", 1, "extra2", 2), result.extras());
    assertTrue(result.disabled(), "标量字段按 patch 覆盖");
  }

  @Test
  @DisplayName("T43 无 insert 且无 id 的 patch 报错；group 子列表畸形在应用时报错")
  void rejectsDegeneratePatches() {
    CordisException noTarget =
        assertThrows(
            CordisException.class,
            () ->
                Patches.apply(
                    List.of(),
                    List.of(
                        new Patch(null, null, null, null, null, null, null, null, null, null))));
    assertTrue(noTarget.getMessage().contains("requires an id"), "覆盖型 patch 必须有 id");

    CordisEntry malformedGroup =
        new CordisEntry("g", "foo", Map.of("not", "a list"), true, false, null, null, null, null);
    CordisException notAList =
        assertThrows(
            CordisException.class,
            () ->
                Patches.apply(
                    List.of(malformedGroup),
                    List.of(insertInto("g", CordisEntry.of("x", "y", null)))));
    assertTrue(notAList.getMessage().contains("must be a list"), "group 的 config 必须是条目列表");
  }

  @Test
  @DisplayName("T43 空参校验：树/层/patch 为 null 或层内 null 元素")
  void requireNonNullArguments() {
    assertThrows(NullPointerException.class, () -> Patches.apply(null, List.of()));
    assertThrows(NullPointerException.class, () -> Patches.apply(List.of(), null));
    assertThrows(NullPointerException.class, () -> Patches.apply(List.of(), List.of((Patch) null)));
    assertThrows(NullPointerException.class, () -> Patch.override(null, "v"));
    assertThrows(NullPointerException.class, () -> Patch.insert((CordisEntry[]) null));
  }
}
