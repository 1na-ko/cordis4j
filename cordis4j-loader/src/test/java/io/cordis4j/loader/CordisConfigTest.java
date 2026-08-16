/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.CordisException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T42: the cordis configuration format layer (decision D28): entry trees read from YAML (upstream's
 * delayed {@code !!js} tag preserved as {@link JsExpr}) and JSON, unknown fields surviving for
 * round-tripping, dispatch by file extension, the strict entry-row shape, and ids generated at read
 * time - plus the interpolation pass a host runs over the delayed nodes.
 */
class CordisConfigTest {

  private static final String FULL_YAML =
      """
      - id: a
        name: foo
        config:
          port: 8080
          host: !!js ctx.host
        inject:
          - logger
        intercept:
          cache:
            level: 2
        isolate:
          config: true
      - id: g
        name: grouped
        group: true
        disabled: true
        config:
          - id: b
            name: bar
          - name: baz
            futureField: 42
      """;

  @TempDir Path tempDir;

  /** Asserts the group's config is a list and returns it as the parsed child rows. */
  @SuppressWarnings("unchecked")
  private static List<CordisEntry> childRows(CordisEntry group) {
    return (List<CordisEntry>) assertInstanceOf(List.class, group.config());
  }

  private Path write(String fileName, String content) throws IOException {
    Path file = tempDir.resolve(fileName);
    Files.writeString(file, content);
    return file;
  }

  @Test
  @DisplayName("T42 YAML 全字段读取：group 嵌套递归解析，inject/intercept/isolate 原样保留")
  void readsEveryYamlField() throws IOException {
    List<CordisEntry> entries = CordisConfig.read(write("cordis.yml", FULL_YAML));

    assertEquals(2, entries.size(), "顶层两行");
    CordisEntry first = entries.get(0);
    assertEquals("a", first.id());
    assertEquals("foo", first.name());
    assertFalse(first.group());
    assertFalse(first.disabled());
    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) first.config();
    assertEquals(8080, config.get("port"), "标量配置原样保留");
    assertEquals(List.of("logger"), first.inject(), "inject 声明逐字保留");
    assertEquals(Map.of("cache", Map.of("level", 2)), first.intercept());
    assertEquals(Map.of("config", true), first.isolate());

    CordisEntry group = entries.get(1);
    assertTrue(group.group());
    assertTrue(group.disabled());
    List<CordisEntry> children = childRows(group);
    assertEquals(2, children.size(), "group 子行递归解析");
    assertEquals("b", children.get(0).id());
    assertEquals("bar", children.get(0).name());
  }

  @Test
  @DisplayName("T42 !!js 节点保留为延迟求值的 JsExpr，读取时不求值")
  void preservesJsExprNodes() throws IOException {
    List<CordisEntry> entries = CordisConfig.read(write("cordis.yaml", FULL_YAML));
    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) entries.get(0).config();
    JsExpr delayed = assertInstanceOf(JsExpr.class, config.get("host"));
    assertEquals("ctx.host", delayed.expression(), "表达式文本逐字保留");
  }

  @Test
  @DisplayName("T42 未知字段保留在 extras 中按文档顺序透传（upstream [key: string]: any）")
  void keepsUnknownFieldsInExtras() throws IOException {
    List<CordisEntry> entries = CordisConfig.read(write("cordis.yml", FULL_YAML));
    CordisEntry generated = childRows(entries.get(1)).get(1);
    assertEquals(Map.of("futureField", 42), generated.extras(), "未识别字段不丢弃");
    assertEquals(List.of("futureField"), List.copyOf(generated.extras().keySet()), "保持文档顺序");
  }

  @Test
  @DisplayName("T42 interpolate 递归替换 map/list 中的 JsExpr，其余节点原样通过")
  void interpolatesJsExprNodes() throws IOException {
    Context ctx = Contexts.create();
    Object tree =
        Map.of(
            "port",
            8080,
            "host",
            new JsExpr("ctx.host"),
            "nested",
            List.of(new JsExpr("ctx.name"), "plain"));
    Object interpolated =
        Interpolators.interpolate(
            tree,
            (expression, context) -> expression.equals("ctx.host") ? "localhost" : "cordis",
            ctx);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) interpolated;
    assertEquals(8080, result.get("port"), "非表达式节点原样通过");
    assertEquals("localhost", result.get("host"));
    assertEquals(List.of("cordis", "plain"), result.get("nested"), "列表内表达式递归替换");
  }

  @Test
  @DisplayName("T42 默认求值器拒绝一切表达式：模块不内置 JS 引擎")
  void defaultEvaluatorRejectsEverything() {
    Context ctx = Contexts.create();
    assertThrows(
        UnsupportedOperationException.class,
        () -> Interpolators.interpolate(new JsExpr("1+1"), ExpressionEvaluator.NONE, ctx),
        "无宿主引擎时表达式必须被拒绝");
    assertNull(Interpolators.interpolate(null, ExpressionEvaluator.NONE, ctx), "null 树返回 null");
  }

  @Test
  @DisplayName("T42 interpolate 的空参校验")
  void interpolateRequiresArguments() {
    Context ctx = Contexts.create();
    assertThrows(NullPointerException.class, () -> Interpolators.interpolate("x", null, ctx));
    assertThrows(
        NullPointerException.class,
        () -> Interpolators.interpolate("x", ExpressionEvaluator.NONE, null));
  }

  @Test
  @DisplayName("T42 按扩展名分派 .yml/.yaml/.json；其余扩展名拒绝")
  void dispatchesByExtension() throws IOException {
    assertEquals(
        "y", CordisConfig.read(write("a.yml", "- name: y\n")).get(0).name(), ".yml 走 YAML");
    assertEquals(
        "v", CordisConfig.read(write("b.yaml", "- name: v\n")).get(0).name(), ".yaml 走 YAML");
    assertEquals(
        "j",
        CordisConfig.read(write("c.json", "[{\"name\": \"j\"}]")).get(0).name(),
        ".json 走 JSON");
    Path other = write("d.txt", "- name: x\n");
    IllegalArgumentException rejected =
        assertThrows(IllegalArgumentException.class, () -> CordisConfig.read(other));
    assertTrue(rejected.getMessage().contains(".txt"), "错误信息指明不支持扩展名");
  }

  @Test
  @DisplayName("T42 JSON 读取等价（Jackson 树，无表达式标签）")
  void readsJson() throws IOException {
    List<CordisEntry> entries =
        CordisConfig.read(
            write(
                "cordis.json",
                """
                [
                  {"id": "j1", "name": "foo", "config": {"port": 9090},
                   "isolate": {"cfg": "shared"}, "unknown": [1, 2]}
                ]
                """));
    CordisEntry entry = entries.get(0);
    assertEquals("j1", entry.id());
    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) entry.config();
    assertEquals(9090, config.get("port"));
    assertEquals(Map.of("cfg", "shared"), entry.isolate());
    assertEquals(Map.of("unknown", List.of(1, 2)), entry.extras(), "JSON 未知字段同样保留");
  }

  @Test
  @DisplayName("T42 缺省 id 读取时生成 8 位十六进制（upstream ensureId）；显式 id 保留")
  void generatesMissingIds() throws IOException {
    List<CordisEntry> entries = CordisConfig.read(write("cordis.yml", FULL_YAML));
    CordisEntry generated = childRows(entries.get(1)).get(1);
    assertTrue(
        generated.id().matches("[0-9a-f]{8}"),
        "生成 id 形如 Math.random().toString(16).slice(2,10)，实际: " + generated.id());
    assertEquals("a", entries.get(0).id(), "显式 id 不覆盖");
  }

  @Test
  @DisplayName("T42 严格行形状：根非列表、行非映射、name 缺失或空白、intercept 非映射均报错")
  void rejectsMalformedShapes() throws IOException {
    CordisException notAList =
        assertThrows(CordisException.class, () -> CordisConfig.read(write("bad1.yml", "a: 1\n")));
    assertTrue(notAList.getMessage().contains("list"), "根必须是条目行列表");
    assertThrows(
        CordisException.class, () -> CordisConfig.read(write("bad2.yml", "- 42\n")), "行必须是映射");
    assertThrows(
        CordisException.class,
        () -> CordisConfig.read(write("bad3.yml", "- id: x\n")),
        "name 字段必填");
    assertThrows(
        CordisException.class,
        () -> CordisConfig.read(write("bad4.yml", "- name: '  '\n")),
        "name 不得空白");
    assertThrows(
        CordisException.class,
        () -> CordisConfig.read(write("bad5.yml", "- name: x\n  intercept: [1]\n")),
        "intercept 必须是映射");
  }

  @Test
  @DisplayName("T42 非法语法文档报错：malformed YAML / JSON 包装为 CordisException")
  void rejectsMalformedDocuments() throws IOException {
    assertThrows(
        CordisException.class,
        () -> CordisConfig.read(write("broken.yml", "- [1, 2\n")),
        "YAML 语法错误必须报错");
    assertThrows(
        CordisException.class,
        () -> CordisConfig.read(write("broken.json", "[{\"name\": ]")),
        "JSON 语法错误必须报错");
  }

  @Test
  @DisplayName("T42 文件不可读包装为 CordisException；!!js 只允许标量")
  void rejectsUnreadableAndNonScalarJs() throws IOException {
    CordisException unreadable =
        assertThrows(CordisException.class, () -> CordisConfig.read(tempDir.resolve("absent.yml")));
    assertTrue(unreadable.getMessage().contains("cannot read"), "IO 失败带原因包装");
    assertThrows(
        CordisException.class,
        () ->
            CordisConfig.read(
                write(
                    "seq.yml",
                    """
                    - name: x
                      config: !!js
                        - a
                    """)),
        "!!js 用于序列必须报错");
  }

  @Test
  @DisplayName("T42 JsExpr 与读取入口的空参校验")
  void requireNonNullArguments() {
    assertThrows(NullPointerException.class, () -> new JsExpr(null));
    assertThrows(NullPointerException.class, () -> CordisConfig.read((Path) null));
    assertThrows(
        NullPointerException.class, () -> CordisConfig.read(null, CordisConfig.Format.YAML));
    assertThrows(
        NullPointerException.class, () -> CordisConfig.read(new java.io.StringReader("[]"), null));
  }
}
