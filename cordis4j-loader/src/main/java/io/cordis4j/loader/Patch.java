/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row of a patch layer ({@code cordis.patch.yml}) - upstream's {@code PatchOptions}: either an
 * insertion (the {@link #insert} list) or an override located by {@link #id}. Exactly as upstream,
 * an override's {@code name} (when present) must match the located row or the patch is skipped, and
 * every other field replaces the target's wholesale ({@code config} replaces, it never
 * deep-merges).
 *
 * @param id locates the target row (required for overrides; for insertions, the group the rows are
 *     appended into - null appends to the root list)
 * @param name the expected component name of the target (null skips the check; the name is a pure
 *     match guard - it never renames the target)
 * @param insert the rows to insert
 * @param config the replacement configuration tree
 * @param group the replacement group flag, or null to keep the target's
 * @param disabled the replacement disabled flag, or null to keep the target's
 * @param inject the replacement dependency declaration
 * @param intercept the replacement interception configuration
 * @param isolate the replacement isolation table
 * @param extras every other field, applied as overrides too
 */
public record Patch(
    String id,
    String name,
    List<CordisEntry> insert,
    Object config,
    Boolean group,
    Boolean disabled,
    Object inject,
    Map<String, Object> intercept,
    Map<String, Object> isolate,
    Map<String, Object> extras) {

  /** Creates a patch, normalizing nullable collections to empty ones. */
  public Patch {
    insert = insert == null ? List.of() : List.copyOf(insert);
    intercept = ordered(intercept);
    isolate = ordered(isolate);
    extras = ordered(extras);
  }

  private static Map<String, Object> ordered(Map<String, Object> value) {
    return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }

  /**
   * Builds an insertion patch appending {@code rows} to the root list.
   *
   * @param rows the rows to insert
   * @return the patch
   * @throws NullPointerException if any row is null
   */
  public static Patch insert(CordisEntry... rows) {
    Objects.requireNonNull(rows, "rows");
    return new Patch(null, null, List.of(rows), null, null, null, null, null, null, null);
  }

  /**
   * Builds an override patch located by {@code id}.
   *
   * @param id the target row id
   * @param config the replacement configuration tree
   * @return the patch
   * @throws NullPointerException if {@code id} is null
   */
  public static Patch override(String id, Object config) {
    Objects.requireNonNull(id, "id");
    return new Patch(id, null, null, config, null, null, null, null, null, null);
  }
}
