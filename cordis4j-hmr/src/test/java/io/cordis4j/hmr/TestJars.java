/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.hmr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compiles plugin sources at test time into jars, so the HMR tests exercise real class loading and
 * unloading rather than mocks.
 */
final class TestJars {

  /** One class of a test plugin: its binary name and Java source. */
  record Source(String className, String code) {}

  private TestJars() {}

  /**
   * Compiles {@code sources} against the running test classpath and packages every generated class
   * into a jar.
   *
   * @param dir a scratch directory
   * @param jarName the jar file name
   * @param sources the classes to compile
   * @return the jar path
   * @throws IOException if writing or packaging fails
   * @throws IllegalStateException if compilation fails
   */
  static Path compileJar(Path dir, String jarName, List<Source> sources) throws IOException {
    Path sourceRoot = Files.createDirectories(dir.resolve("src"));
    Path classes = Files.createDirectories(dir.resolve("classes-" + jarName));
    for (Source source : sources) {
      Path sourceFile = sourceRoot.resolve(source.className().replace('.', '/') + ".java");
      Files.createDirectories(sourceFile.getParent());
      Files.writeString(sourceFile, source.code(), StandardCharsets.UTF_8);
    }
    List<String> files =
        sources.stream()
            .map(s -> sourceRoot.resolve(s.className().replace('.', '/') + ".java").toString())
            .toList();
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    String[] args =
        List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString())
            .toArray(new String[0]);
    int result = compiler.run(null, null, diagnostics, concat(args, files.toArray(new String[0])));
    if (result != 0) {
      throw new IllegalStateException(
          "test plugin compilation failed:\n" + diagnostics.toString(StandardCharsets.UTF_8));
    }
    return packageJar(dir.resolve(jarName), classes);
  }

  /** Writes a corrupt (non-zip) file for failure-path tests. */
  static Path writeGarbage(Path dir, String name) throws IOException {
    Path file = dir.resolve(name);
    Files.write(file, new byte[] {1, 2, 3, 4, 5});
    return file;
  }

  private static String[] concat(String[] first, String[] second) {
    String[] joined = new String[first.length + second.length];
    System.arraycopy(first, 0, joined, 0, first.length);
    System.arraycopy(second, 0, joined, first.length, second.length);
    return joined;
  }

  private static Path packageJar(Path jar, Path classes) throws IOException {
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      try (var stream = Files.walk(classes)) {
        for (Path file : stream.filter(Files::isRegularFile).toList()) {
          String name = classes.relativize(file).toString().replace('\\', '/');
          out.putNextEntry(new JarEntry(name));
          Files.copy(file, out);
          out.closeEntry();
        }
      }
    }
    return jar;
  }
}
