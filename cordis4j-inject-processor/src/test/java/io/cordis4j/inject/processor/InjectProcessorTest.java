/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.inject.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T28: compile-time generation of annotation injection (paper Section 6.4) - the processor emits a
 * zero-reflection injector per class, rejects misconfigured fields at compile time, and the
 * generated accessors follow the same snapshot semantics as the runtime form (D21).
 */
class InjectProcessorTest {

  @TempDir Path dir;

  record Source(String className, String code) {}

  record Compilation(boolean success, String diagnostics, Path output) {}

  private Compilation compile(String className, String source) throws Exception {
    return compile(List.of(new Source(className, source)));
  }

  private Compilation compile(List<Source> sources) throws Exception {
    Path src = dir.resolve("src-" + System.nanoTime());
    List<String> files = new ArrayList<>();
    for (Source source : sources) {
      Path sourceFile = src.resolve(source.className().replace('.', '/') + ".java");
      Files.createDirectories(sourceFile.getParent());
      Files.writeString(sourceFile, source.code(), StandardCharsets.UTF_8);
      files.add(sourceFile.toString());
    }
    Path out = Files.createDirectories(dir.resolve("out-" + System.nanoTime()));
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    List<String> arguments =
        new ArrayList<>(
            List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-processorpath",
                System.getProperty("java.class.path"),
                "-processor",
                "io.cordis4j.inject.processor.CordisInjectProcessor",
                "-d",
                out.toString()));
    arguments.addAll(files);
    int result = compiler.run(null, null, diagnostics, arguments.toArray(new String[0]));
    return new Compilation(result == 0, diagnostics.toString(StandardCharsets.UTF_8), out);
  }

  private static final String DATABASE = "package sample; public record Database(String url) {}";

  private static final String GREETER =
      "package sample;\n"
          + "public class Greeter {\n"
          + "  @io.cordis4j.core.Inject\n"
          + "  sample.Database database;\n"
          + "}\n";

  @Test
  @DisplayName("T28 处理器生成免反射 injector；生成代码运行快照语义（提供/撤回/重激活）")
  void generatesZeroReflectionInjector() throws Exception {
    Compilation compilation =
        compile(
            List.of(
                new Source("sample.Database", DATABASE), new Source("sample.Greeter", GREETER)));
    assertTrue(compilation.success(), () -> compilation.diagnostics());
    assertTrue(
        Files.exists(compilation.output().resolve("sample/GreeterCordisInjector.class")),
        "必须生成 injector 类");

    try (URLClassLoader loader =
        new URLClassLoader(
            new URL[] {compilation.output().toUri().toURL()}, getClass().getClassLoader())) {
      Class<?> greeterClass = loader.loadClass("sample.Greeter");
      Object greeter = greeterClass.getDeclaredConstructor().newInstance();
      Class<?> injectorClass = loader.loadClass("sample.GreeterCordisInjector");
      Method injectFields = injectorClass.getMethod("injectFields", Context.class, greeterClass);
      Field field = greeterClass.getDeclaredField("database");
      field.setAccessible(true);

      Context ctx = Contexts.create();
      Disposable declaration = (Disposable) injectFields.invoke(null, ctx, greeter);
      assertNull(field.get(greeter), "依赖缺失时字段必须为 null");

      Class<?> databaseClass = loader.loadClass("sample.Database");
      Object database = databaseClass.getConstructor(String.class).newInstance("jdbc:main");
      Disposable provider =
          ctx.plugin(
              c -> {
                c.provide(database); // keyed by the concrete class, as the generated code declares
                return io.cordis4j.core.Disposables.none();
              });
      assertNotNull(field.get(greeter), "满足后必须填充");

      provider.dispose();
      assertNull(field.get(greeter), "撤回后必须清空");

      Object replacement = databaseClass.getConstructor(String.class).newInstance("jdbc:again");
      ctx.plugin(
          c -> {
            c.provide(replacement);
            return io.cordis4j.core.Disposables.none();
          });
      assertNotNull(field.get(greeter), "再满足必须重填");
      declaration.dispose();
      assertNull(field.get(greeter), "退役必须清空");
    }
  }

  @Test
  @DisplayName("T28 private 字段在编译期报错（生成代码需要直接赋值）")
  void privateFieldFailsCompilation() throws Exception {
    Compilation result =
        compile(
            "sample.PrivateGreeter",
            "package sample;\n"
                + "public class PrivateGreeter {\n"
                + "  @io.cordis4j.core.Inject\n"
                + "  private sample.Database database;\n"
                + "}\n");
    assertFalse(result.success(), "private 字段必须使编译失败");
    assertTrue(result.diagnostics().contains("must not be private"), () -> result.diagnostics());
  }

  @Test
  @DisplayName("T28 static/final/原始类型字段在编译期报错")
  void staticFinalPrimitiveFieldsFailCompilation() throws Exception {
    Compilation result =
        compile(
            "sample.BrokenGreeter",
            "package sample;\n"
                + "public class BrokenGreeter {\n"
                + "  @io.cordis4j.core.Inject static sample.Database statik;\n"
                + "  @io.cordis4j.core.Inject final sample.Database fin = null;\n"
                + "  @io.cordis4j.core.Inject int slots;\n"
                + "}\n");
    assertFalse(result.success(), "误配置字段必须使编译失败");
    String diagnostics = result.diagnostics();
    assertTrue(diagnostics.contains("must not be static"), () -> diagnostics);
    assertTrue(diagnostics.contains("must not be final"), () -> diagnostics);
    assertTrue(diagnostics.contains("must have a reference type"), () -> diagnostics);
  }

  @Test
  @DisplayName("T28 无注解字段的类不生成 injector；字段必须位于命名包")
  void noAnnotatedFieldsGeneratesNothing() throws Exception {
    Compilation plain =
        compile("sample.Plain", "package sample; public class Plain { public String name; }");
    assertTrue(plain.success(), () -> plain.diagnostics());
    assertFalse(Files.exists(plain.output().resolve("sample/PlainCordisInjector.class")));
  }

  @Test
  @DisplayName("T28 其余编译期约束：非 public 类、嵌套类、默认包、注解误用均报错")
  void remainingCompileTimeConstraints() throws Exception {
    Compilation nonPublic =
        compile(
            "sample.PackageLocal",
            "package sample;\n"
                + "class PackageLocal {\n"
                + "  @io.cordis4j.core.Inject sample.Database database;\n"
                + "}\n");
    assertFalse(nonPublic.success(), "非 public 类必须失败");
    assertTrue(
        nonPublic.diagnostics().contains("require a public class"), () -> nonPublic.diagnostics());

    Compilation nested =
        compile(
            "sample.Outer",
            "package sample;\n"
                + "public class Outer {\n"
                + "  public static class Inner {\n"
                + "    @io.cordis4j.core.Inject sample.Database database;\n"
                + "  }\n"
                + "}\n");
    assertFalse(nested.success(), "嵌套类必须失败");
    assertTrue(nested.diagnostics().contains("top-level classes only"), () -> nested.diagnostics());

    Compilation defaultPackage =
        compile(
            "DefaultGreeter",
            "public class DefaultGreeter {\n"
                + "  @io.cordis4j.core.Inject java.lang.String name;\n"
                + "}\n");
    assertFalse(defaultPackage.success(), "默认包必须失败");
    assertTrue(
        defaultPackage.diagnostics().contains("require a named package"),
        () -> defaultPackage.diagnostics());

    Compilation misused =
        compile(
            "sample.Misused",
            "package sample;\n"
                + "public class Misused {\n"
                + "  @io.cordis4j.core.Inject\n"
                + "  void method() {}\n"
                + "}\n");
    assertFalse(misused.success(), "注解误用必须失败（javac 的 @Target 检查兜底）");
  }
}
