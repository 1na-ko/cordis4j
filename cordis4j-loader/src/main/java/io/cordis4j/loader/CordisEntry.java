/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One entry row of a cordis configuration tree - the faithful JVM form of upstream's {@code
 * EntryOptions} (loader and include plugins): every field upstream defines is preserved, and
 * unknown fields survive in {@link #extras} for round-tripping, matching upstream's {@code [key:
 * string]: any}.
 *
 * <p>A group entry's {@link #config} is the nested {@code List<CordisEntry>} (recursively parsed by
 * {@link CordisConfig#read}); other entries carry an arbitrary configuration tree whose {@link
 * JsExpr} nodes stay delayed until the host interpolates them.
 *
 * @param id the instance identifier; generated as 8 hex digits when the row omits it (at read time,
 *     once - re-reading generates fresh ids, so stable mounts want explicit ids)
 * @param name the component name (host convention), never null
 * @param config the configuration tree; a list of entries for groups
 * @param group whether this row is a group (always enabled itself; its disabled flag still applies
 *     to the subtree below it)
 * @param disabled whether this row (and its subtree) is not mounted
 * @param inject the dependency declaration, upstream's shape: a list of service names or a map of
 *     name to option, kept verbatim for the host
 * @param intercept the service interception configuration (service name to metadata tree)
 * @param isolate the isolation table (service name to {@code true} for a local realm or a label
 *     string for a shared realm)
 * @param extras every unrecognized field, in document order
 */
public record CordisEntry(
    String id,
    String name,
    Object config,
    boolean group,
    boolean disabled,
    Object inject,
    Map<String, Object> intercept,
    Map<String, Object> isolate,
    Map<String, Object> extras) {

  /**
   * Creates an entry, normalizing the nullable maps to empty ones.
   *
   * @throws NullPointerException if {@code name} is null
   */
  public CordisEntry {
    Objects.requireNonNull(name, "name");
    intercept = ordered(intercept);
    isolate = ordered(isolate);
    extras = ordered(extras);
  }

  /**
   * Copies a field map preserving document order (the isolation table nests per service in table
   * order, extras survive in document order), unmodifiable either way.
   */
  private static Map<String, Object> ordered(Map<String, Object> value) {
    return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }

  /**
   * Builds a plain non-group entry.
   *
   * @param id the instance identifier
   * @param name the component name
   * @param config the configuration tree
   * @return the entry
   * @throws NullPointerException if {@code name} is null
   */
  public static CordisEntry of(String id, String name, Object config) {
    return new CordisEntry(id, name, config, false, false, null, null, null, null);
  }

  /**
   * Builds a group entry whose config is the given children.
   *
   * @param id the group identifier
   * @param children the nested entries
   * @return the group entry
   * @throws NullPointerException if any child or its name is null
   */
  public static CordisEntry group(String id, java.util.List<CordisEntry> children) {
    return new CordisEntry(
        id,
        "@group",
        java.util.List.copyOf(children),
        true,
        false,
        null,
        new LinkedHashMap<>(),
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }
}
