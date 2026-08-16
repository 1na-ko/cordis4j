/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cordis4j.core.CordisException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The two dsh manifests, both living under the {@code dsh} key of a {@code package.json}:
 *
 * <ul>
 *   <li><b>bundle</b> - what an author distributes: {@code dsh.bundle.patch} names the package's
 *       configuration layer file (relative to the package root). A package without the declaration
 *       is an ordinary dependency that activates nothing.
 *   <li><b>profile</b> - what a user composes: {@code dsh.profile.bundles} is the ordered list of
 *       bundle names whose layers stack (in order), before the profile's own {@code
 *       cordis.patch.yml}.
 * </ul>
 *
 * <p>Everything else a dsh host does around these manifests (package-manager installs, bundle
 * resolution through node_modules, home-level overlays) is host policy and stays outside this
 * module.
 */
public final class Manifests {

  /**
   * A bundle's declaration: its layer file, resolved against the package root.
   *
   * @param patch the bundle's patch-layer file, resolved against the package root
   */
  public record Bundle(Path patch) {}

  /**
   * A profile's declaration: the ordered bundle names to stack.
   *
   * @param bundles the bundle names whose layers stack, in order
   */
  public record Profile(List<String> bundles) {

    /** Creates the profile, copying the bundle list. */
    public Profile {
      Objects.requireNonNull(bundles, "bundles");
      bundles = List.copyOf(bundles);
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger(Manifests.class.getName());

  private Manifests() {}

  /**
   * Reads a package's bundle manifest.
   *
   * @param packageJson the package's {@code package.json}
   * @return the bundle declaration, or empty when the package declares none
   * @throws CordisException if the file cannot be read or the declaration is malformed
   * @throws NullPointerException if {@code packageJson} is null
   */
  public static Optional<Bundle> bundle(Path packageJson) {
    Objects.requireNonNull(packageJson, "packageJson");
    Map<String, Object> root = readPackageJson(packageJson);
    Object declared = manifestSection(root, "bundle");
    if (declared == null) {
      return Optional.empty();
    }
    if (!(declared instanceof Map<?, ?> section)) {
      LOG.warning(() -> "dsh.bundle is not an object, ignored: " + packageJson);
      return Optional.empty();
    }
    Object patchField = section.get("patch");
    if (!(patchField instanceof String patch) || patch.isBlank()) {
      throw new CordisException("dsh.bundle requires a non-blank patch: " + packageJson);
    }
    return Optional.of(
        new Bundle(packageJson.toAbsolutePath().getParent().resolve(patch).normalize()));
  }

  /**
   * Reads a package's profile manifest.
   *
   * @param packageJson the profile's {@code package.json}
   * @return the profile declaration, or empty when the package declares none
   * @throws CordisException if the file cannot be read or the declaration is malformed
   * @throws NullPointerException if {@code packageJson} is null
   */
  public static Optional<Profile> profile(Path packageJson) {
    Objects.requireNonNull(packageJson, "packageJson");
    Map<String, Object> root = readPackageJson(packageJson);
    Object declared = manifestSection(root, "profile");
    if (declared == null) {
      return Optional.empty();
    }
    if (!(declared instanceof Map<?, ?> section)) {
      LOG.warning(() -> "dsh.profile is not an object, ignored: " + packageJson);
      return Optional.empty();
    }
    Object bundlesField = section.get("bundles");
    if (!(bundlesField instanceof List<?> names) || names.isEmpty()) {
      throw new CordisException("dsh.profile requires a non-empty bundles list: " + packageJson);
    }
    List<String> bundles = new ArrayList<>(names.size());
    for (Object name : names) {
      if (!(name instanceof String value) || value.isBlank()) {
        throw new CordisException("dsh.profile.bundles entries must be non-blank strings");
      }
      bundles.add(value);
    }
    return Optional.of(new Profile(bundles));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readPackageJson(Path packageJson) {
    Object document;
    try {
      document = JSON.readValue(packageJson.toFile(), Object.class);
    } catch (IOException failure) {
      throw new CordisException("cannot read manifest: " + packageJson, failure);
    }
    if (!(document instanceof Map<?, ?> map)) {
      throw new CordisException("a package.json must be an object: " + packageJson);
    }
    return (Map<String, Object>) map;
  }

  private static Object manifestSection(Map<String, Object> root, String kind) {
    Object dsh = root.get("dsh");
    if (!(dsh instanceof Map<?, ?> sections)) {
      return null;
    }
    return sections.get(kind);
  }
}
