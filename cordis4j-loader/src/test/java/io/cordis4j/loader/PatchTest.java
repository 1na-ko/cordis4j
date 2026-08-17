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
 *
 * <p>T55: broken insertion targets (missing row, non-group row, malformed children) skip the patch
 * with a warning instead of throwing, an explicit empty {@code insert} list still dispatches as an
 * insertion, and an override's {@code intercept}/{@code isolate} tables replace the target's
 * wholesale (only {@code extras} keep per-key merging) - matching upstream include exactly.
 */
class PatchTest {

  private static List<CordisEntry> children(CordisEntry group) {
    @SuppressWarnings("unchecked")
    List<CordisEntry> children = (List<CordisEntry>) group.config();
    return children;
  }

  private static Patch insertInto(String groupId, CordisEntry... rows) {
    return new Patch(groupId, null, true, List.of(rows), null, null, null, null, null, null, null);
  }

  private static Patch overrideWith(String id, String name, Object config) {
    return new Patch(id, name, false, null, config, null, null, null, null, null, null);
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
  @DisplayName("T55 insert 目标缺失或目标不是 group：告警跳过，树不变（不再抛异常）")
  void skipsBrokenInsertionTargets() {
    List<CordisEntry> missingResult =
        Patches.apply(List.of(), List.of(insertInto("nowhere", CordisEntry.of("x", "y", null))));
    assertEquals(List.of(), missingResult, "缺失目标必须告警跳过，树保持原样");

    CordisEntry notAGroup = CordisEntry.of("plain", "foo", null);
    List<CordisEntry> wrongKind =
        Patches.apply(
            List.of(notAGroup), List.of(insertInto("plain", CordisEntry.of("x", "y", null))));
    assertEquals(List.of(notAGroup), wrongKind, "非 group 目标必须告警跳过，树不变");
  }

  @Test
  @DisplayName("T55 空 insert 列表仍走 insert 分派：目标存在不改内容，目标缺失跳过")
  void emptyInsertListStillDispatchesAsInsertion() {
    CordisEntry leaf = CordisEntry.of("c", "foo", null);
    CordisEntry group = CordisEntry.group("g", List.of(leaf));
    Patch emptyInsert = insertInto("g");

    List<CordisEntry> patched = Patches.apply(List.of(group), List.of(emptyInsert));
    assertEquals(List.of(group), patched, "空 insert 命中存在的 group 时不改内容");

    List<CordisEntry> skipped = Patches.apply(List.of(group), List.of(insertInto("ghost")));
    assertEquals(List.of(group), skipped, "空 insert 目标缺失时告警跳过");
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
  @DisplayName("T55 覆盖的 intercept/isolate 整表替换（keep 消失）；extras 仍按键覆盖")
  void replacesTablesWholesaleOnOverride() {
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
            false,
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
    assertEquals(Map.of("cache", "new"), result.intercept(), "intercept 整表替换：目标的 keep 必须消失");
    assertEquals(Map.of("logger", "L"), result.isolate(), "isolate 整表替换：目标的 config 必须消失");
    assertEquals(Map.of("extra1", 1, "extra2", 2), result.extras(), "extras 仍按键覆盖合并");
    assertTrue(result.disabled(), "标量字段按 patch 覆盖");
  }

  @Test
  @DisplayName("T43 无 insert 且无 id 的 patch 报错；override 遇畸形 group 子列表报错")
  void rejectsDegeneratePatches() {
    CordisException noTarget =
        assertThrows(
            CordisException.class,
            () ->
                Patches.apply(
                    List.of(),
                    List.of(
                        new Patch(
                            null, null, false, null, null, null, null, null, null, null, null))));
    assertTrue(noTarget.getMessage().contains("requires an id"), "覆盖型 patch 必须有 id");

    CordisEntry malformedGroup =
        new CordisEntry("g", "foo", Map.of("not", "a list"), true, false, null, null, null, null);
    CordisException notAList =
        assertThrows(
            CordisException.class,
            () -> Patches.apply(List.of(malformedGroup), List.of(Patch.override("any", "v"))));
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
