/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cordis4j.core.CordisException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T44: the two dsh manifests (decision D28): a bundle declares its layer file under {@code
 * dsh.bundle.patch} (resolved against the package root), a profile declares the ordered {@code
 * dsh.profile.bundles} stack, packages without the {@code dsh} key are plain dependencies, and
 * malformed declarations fail loudly.
 */
class ManifestTest {

  @TempDir Path tempDir;

  private Path packageJson(String content) throws IOException {
    Path file = tempDir.resolve("package.json");
    Files.writeString(file, content);
    return file;
  }

  @Test
  @DisplayName("T44 bundle 清单：patch 路径相对 package 根解析并规范化")
  void readsBundleManifest() throws IOException {
    Path manifest =
        packageJson(
            """
            {"name": "my-bundle", "dsh": {"bundle": {"patch": "cordis.patch.yml"}}}
            """);

    Optional<Manifests.Bundle> bundle = Manifests.bundle(manifest);

    assertTrue(bundle.isPresent(), "声明了 dsh.bundle 即为 bundle");
    assertEquals(
        tempDir.resolve("cordis.patch.yml").normalize(),
        bundle.get().patch(),
        "patch 路径相对 package.json 所在目录解析");
  }

  @Test
  @DisplayName("T44 无 dsh 键或无对应小节 → 空清单（普通依赖不激活任何层）")
  void emptyWhenUndeclared() throws IOException {
    assertEquals(
        Optional.empty(),
        Manifests.bundle(packageJson("{\"name\": \"plain\"}")),
        "无 dsh 键的包不是 bundle");
    assertEquals(
        Optional.empty(),
        Manifests.profile(packageJson("{\"name\": \"plain\"}")),
        "无 dsh 键的包不是 profile");
    assertEquals(
        Optional.empty(),
        Manifests.bundle(packageJson("{\"dsh\": {\"profile\": {\"bundles\": [\"a\"]}}}")),
        "声明 profile 的包不是 bundle");
  }

  @Test
  @DisplayName("T44 profile 清单：bundles 有序保留（层的叠放顺序）")
  void readsProfileBundlesInOrder() throws IOException {
    Path manifest =
        packageJson(
            """
            {"name": "my-profile", "dsh": {"profile": {"bundles": ["base", "extra", "local"]}}}
            """);

    Optional<Manifests.Profile> profile = Manifests.profile(manifest);

    assertEquals(
        Optional.of(new Manifests.Profile(java.util.List.of("base", "extra", "local"))), profile);
    assertEquals(
        java.util.List.of("base", "extra", "local"),
        profile.orElseThrow().bundles(),
        "bundles 顺序即叠层顺序");
  }

  @Test
  @DisplayName("T44 bundle.patch 非字符串或空白 → 报错")
  void rejectsMalformedBundleDeclarations() throws IOException {
    assertThrows(
        CordisException.class,
        () -> Manifests.bundle(packageJson("{\"dsh\": {\"bundle\": {\"patch\": 42}}}")),
        "patch 必须是字符串");
    assertThrows(
        CordisException.class,
        () -> Manifests.bundle(packageJson("{\"dsh\": {\"bundle\": {}}}")),
        "缺 patch 报错");
    assertThrows(
        CordisException.class,
        () -> Manifests.bundle(packageJson("{\"dsh\": {\"bundle\": {\"patch\": \"  \"}}}")),
        "空白 patch 报错");
  }

  @Test
  @DisplayName("T44 profile.bundles 空列表或含非字符串 → 报错")
  void rejectsMalformedProfileDeclarations() throws IOException {
    assertThrows(
        CordisException.class,
        () -> Manifests.profile(packageJson("{\"dsh\": {\"profile\": {\"bundles\": []}}}")),
        "空 bundles 报错");
    assertThrows(
        CordisException.class,
        () -> Manifests.profile(packageJson("{\"dsh\": {\"profile\": {\"bundles\": [\"a\", 7]}}}")),
        "非字符串成员报错");
    assertThrows(
        CordisException.class,
        () -> Manifests.profile(packageJson("{\"dsh\": {\"profile\": {}}}")),
        "缺 bundles 报错");
  }

  @Test
  @DisplayName("T44 清单不可读或根非对象 → CordisException")
  void rejectsUnreadableManifests() throws IOException {
    CordisException absent =
        assertThrows(CordisException.class, () -> Manifests.bundle(tempDir.resolve("absent.json")));
    assertTrue(absent.getMessage().contains("cannot read"), "IO 失败带原因包装");
    assertThrows(
        CordisException.class,
        () -> Manifests.profile(packageJson("[1, 2]")),
        "package.json 根必须是对象");
  }

  @Test
  @DisplayName("T44 空参校验")
  void requireNonNullArguments() {
    assertThrows(NullPointerException.class, () -> Manifests.bundle(null));
    assertThrows(NullPointerException.class, () -> Manifests.profile(null));
    assertThrows(NullPointerException.class, () -> new Manifests.Profile(null));
  }
}
