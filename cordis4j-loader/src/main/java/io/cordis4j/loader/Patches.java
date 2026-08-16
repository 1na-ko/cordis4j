/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import io.cordis4j.core.CordisException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Applies patch layers onto an entry tree with upstream include's semantics:
 *
 * <ul>
 *   <li>an insertion without an {@code id} appends its rows to the root list; with an {@code id} it
 *       appends them to that group's children (the group must exist);
 *   <li>an override locates its row by {@code id} anywhere in the tree (group children included); a
 *       {@code name} that does not match the located row skips the patch with a warning; every
 *       other field replaces the target's wholesale - {@code config} replaces, it never
 *       deep-merges;
 *   <li>a later patch in the same layer may locate a row an earlier patch inserted.
 * </ul>
 *
 * <p>Layering is repeated application: the host starts from an initial tree (often empty) and
 * applies each layer's patches in order; later layers win by construction.
 */
public final class Patches {

  private static final Logger LOG = Logger.getLogger(Patches.class.getName());

  private Patches() {}

  /**
   * Applies the patches of one layer onto an entry tree.
   *
   * @param tree the current entry tree
   * @param patches the layer's patches, in order
   * @return a new tree with every patch applied (the input tree is untouched)
   * @throws CordisException when an insertion targets a missing group, or a located row is not a
   *     group
   * @throws NullPointerException if any argument or element is null
   */
  public static List<CordisEntry> apply(List<CordisEntry> tree, List<Patch> patches) {
    Objects.requireNonNull(tree, "tree");
    Objects.requireNonNull(patches, "patches");
    List<CordisEntry> current = new ArrayList<>(tree);
    for (Patch patch : patches) {
      Objects.requireNonNull(patch, "patch");
      if (!patch.insert().isEmpty()) {
        current = applyInsert(current, patch);
      } else {
        current = applyOverride(current, patch);
      }
    }
    return List.copyOf(current);
  }

  private static List<CordisEntry> applyInsert(List<CordisEntry> tree, Patch patch) {
    if (patch.id() == null) {
      List<CordisEntry> grown = new ArrayList<>(tree);
      grown.addAll(patch.insert());
      return grown;
    }
    List<CordisEntry> grown = insertIntoGroup(tree, patch);
    if (grown == null) {
      throw new CordisException("insertion target not found: " + patch.id());
    }
    return grown;
  }

  /** Inserts into the located group, recursing through nested ones; null when no row matches. */
  private static List<CordisEntry> insertIntoGroup(List<CordisEntry> tree, Patch patch) {
    List<CordisEntry> rebuilt = new ArrayList<>(tree.size());
    boolean inserted = false;
    for (CordisEntry entry : tree) {
      if (entry.id().equals(patch.id())) {
        if (!entry.group()) {
          throw new CordisException("insertion target is not a group: " + patch.id());
        }
        List<CordisEntry> children = new ArrayList<>(groupChildren(entry));
        children.addAll(patch.insert());
        rebuilt.add(withConfig(entry, List.copyOf(children)));
        inserted = true;
      } else if (entry.group()) {
        List<CordisEntry> children = insertIntoGroup(groupChildren(entry), patch);
        if (children != null) {
          rebuilt.add(withConfig(entry, children));
          inserted = true; // the target sits inside this subtree
        } else {
          rebuilt.add(entry);
        }
      } else {
        rebuilt.add(entry);
      }
    }
    return inserted ? rebuilt : null;
  }

  private static List<CordisEntry> applyOverride(List<CordisEntry> tree, Patch patch) {
    if (patch.id() == null) {
      throw new CordisException("a patch without insert requires an id");
    }
    return overrideIn(tree, patch);
  }

  private static List<CordisEntry> overrideIn(List<CordisEntry> tree, Patch patch) {
    List<CordisEntry> rebuilt = new ArrayList<>(tree.size());
    boolean applied = false;
    for (CordisEntry entry : tree) {
      if (entry.id().equals(patch.id())) {
        if (patch.name() != null && !patch.name().equals(entry.name())) {
          LOG.warning(
              () ->
                  "patch for '"
                      + patch.id()
                      + "' skipped: name mismatch (expected "
                      + patch.name()
                      + ", found "
                      + entry.name()
                      + ")");
          rebuilt.add(entry);
          applied = true; // matched the row; the patch itself is a no-op
        } else {
          rebuilt.add(overrideEntry(entry, patch));
          applied = true;
        }
      } else if (entry.group()) {
        List<CordisEntry> children = overrideIn(groupChildren(entry), patch);
        rebuilt.add(children.equals(groupChildren(entry)) ? entry : withConfig(entry, children));
        if (!children.equals(groupChildren(entry))) {
          applied = true;
        }
      } else {
        rebuilt.add(entry);
      }
    }
    if (!applied) {
      LOG.warning(() -> "patch target not found: " + patch.id());
    }
    return rebuilt;
  }

  private static CordisEntry overrideEntry(CordisEntry entry, Patch patch) {
    return new CordisEntry(
        entry.id(),
        entry.name(), // name is a match guard, never a rename (upstream semantics)
        patch.config() != null ? patch.config() : entry.config(),
        patch.group() != null ? patch.group() : entry.group(),
        patch.disabled() != null ? patch.disabled() : entry.disabled(),
        patch.inject() != null ? patch.inject() : entry.inject(),
        mergeMaps(entry.intercept(), patch.intercept()),
        mergeMaps(entry.isolate(), patch.isolate()),
        mergeMaps(entry.extras(), patch.extras()));
  }

  private static Map<String, Object> mergeMaps(
      Map<String, Object> base, Map<String, Object> overrides) {
    if (overrides.isEmpty()) {
      return base;
    }
    Map<String, Object> merged = new LinkedHashMap<>(base);
    merged.putAll(overrides);
    return java.util.Collections.unmodifiableMap(merged);
  }

  @SuppressWarnings("unchecked")
  private static List<CordisEntry> groupChildren(CordisEntry group) {
    if (group.config() instanceof List<?> children) {
      return (List<CordisEntry>) children;
    }
    throw new CordisException("a group entry's config must be a list of entries: " + group.id());
  }

  private static CordisEntry withConfig(CordisEntry entry, Object config) {
    return new CordisEntry(
        entry.id(),
        entry.name(),
        config,
        entry.group(),
        entry.disabled(),
        entry.inject(),
        entry.intercept(),
        entry.isolate(),
        entry.extras());
  }
}
