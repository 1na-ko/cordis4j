/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import io.cordis4j.core.ComponentSpec;
import io.cordis4j.core.CordisException;
import io.cordis4j.core.Plugin;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a cordis entry tree onto the core's {@link ComponentSpec} composition - the last mile a host
 * needs before {@code Loader.reconcileTree}:
 *
 * <ul>
 *   <li>groups become {@link ComponentSpec.Group} (the engine prefixes children ids);
 *   <li>a group's {@code isolate} and {@code intercept} tables propagate down the prototype chain,
 *       nearer rows overriding the same service name (upstream's group inheritance);
 *   <li>an entry's isolation table becomes nested {@link ComponentSpec.Isolate} realms, one per
 *       service, in table order (the first table service wraps outermost): {@code true} is a local
 *       realm ({@code '#<entryId>'}, unique to the entry, upstream's LocalRealm) and a label string
 *       is a shared realm ({@code '@<label>'}, shared by every entry using it, upstream's
 *       GlobalRealm); falsy values (null, false, empty string) mount no realm for that service;
 *   <li>disabled entries drop out, a group's own flag included in the inheritance chain (a group
 *       itself is never dropped - it just stops mounting its children);
 *   <li>{@code inject} and {@code intercept} declarations survive per entry in {@link EntryMeta}
 *       for the host to apply (typed keys are host knowledge, decision D28), with the interception
 *       table already merged over the inherited chain.
 * </ul>
 */
public final class CordisSpecs {

  /**
   * The per-entry knowledge the mapping cannot apply itself: the configuration tree, the dependency
   * declaration (upstream's shape, verbatim), and the interception configuration (service name to
   * metadata tree, merged over the inherited chain).
   *
   * @param id the entry id, prefixed like the core's flattened ids ({@code group:child} through the
   *     group chain) so {@link Mapping#meta()} keys join a reconciled tree by id
   * @param name the component name
   * @param config the entry's configuration tree, verbatim (JsExpr nodes still delayed)
   * @param inject the dependency declaration, upstream's shape
   * @param intercept the interception configuration
   */
  public record EntryMeta(
      String id, String name, Object config, Object inject, Map<String, Object> intercept) {

    /** Creates the metadata, normalizing the intercept map. */
    public EntryMeta {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      intercept =
          intercept == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(intercept));
    }
  }

  /**
   * The mapping result: the composition tree plus the per-entry metadata.
   *
   * @param specs the composition tree, in entry order
   * @param meta the per-entry metadata, keyed by flattened entry id
   */
  public record Mapping(List<ComponentSpec> specs, Map<String, EntryMeta> meta) {

    /** Creates the mapping, copying both collections (metadata keeps entry order). */
    public Mapping {
      Objects.requireNonNull(specs, "specs");
      Objects.requireNonNull(meta, "meta");
      specs = List.copyOf(specs);
      meta = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }
  }

  private CordisSpecs() {}

  /**
   * Maps an entry tree onto a composition tree.
   *
   * @param entries the entry tree (as parsed by {@link CordisConfig} or built by {@link Patches})
   * @param baseUrl the base directory component names resolve against
   * @param resolver the host's name-to-component policy
   * @return the composition tree and per-entry metadata
   * @throws CordisException when an entry lacks an id (unstable mount - give rows ids or read them
   *     through {@link CordisConfig}, which generates one), or the flattened ids collide (two rows
   *     reaching the same {@code group:child} key)
   * @throws NullPointerException if any argument or element is null
   */
  public static Mapping toSpecs(
      List<CordisEntry> entries, Path baseUrl, ComponentResolver resolver) {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(resolver, "resolver");
    List<ComponentSpec> specs = new ArrayList<>(entries.size());
    Map<String, EntryMeta> meta = new LinkedHashMap<>();
    for (CordisEntry entry : entries) {
      expand(entry, "", false, Map.of(), Map.of(), baseUrl, resolver, specs, meta);
    }
    return new Mapping(specs, meta);
  }

  private static void expand(
      CordisEntry entry,
      String prefix,
      boolean inheritedDisabled,
      Map<String, Object> inheritedIsolate,
      Map<String, Object> inheritedIntercept,
      Path baseUrl,
      ComponentResolver resolver,
      List<ComponentSpec> out,
      Map<String, EntryMeta> meta) {
    requireId(entry);
    String flattenedId = prefix + entry.id();
    if (entry.group()) {
      // A group is never dropped itself; its disabled flag still extends the chain below it, and
      // its isolate/intercept tables extend the inherited chain (nearer rows win per service).
      Map<String, Object> childIsolate = chain(inheritedIsolate, entry.isolate());
      Map<String, Object> childIntercept = chain(inheritedIntercept, entry.intercept());
      List<ComponentSpec> children = new ArrayList<>();
      for (CordisEntry child : children(entry)) {
        expand(
            child,
            flattenedId + ":",
            inheritedDisabled || entry.disabled(),
            childIsolate,
            childIntercept,
            baseUrl,
            resolver,
            children,
            meta);
      }
      out.add(new ComponentSpec.Group(entry.id(), children));
      return;
    }
    if (meta.putIfAbsent(
            flattenedId,
            new EntryMeta(
                flattenedId,
                entry.name(),
                entry.config(),
                entry.inject(),
                chain(inheritedIntercept, entry.intercept())))
        != null) {
      throw new CordisException("duplicate flattened entry id: " + flattenedId);
    }
    if (inheritedDisabled || entry.disabled()) {
      return; // present in metadata, absent from the mount
    }
    Plugin component = resolver.resolve(entry.name(), baseUrl);
    out.add(
        wrapIsolation(
            chain(inheritedIsolate, entry.isolate()),
            entry.id(),
            new ComponentSpec.Entry(entry.id(), component),
            resolver));
  }

  /** Merges a nearer table over the inherited one, per service name, dropping falsy labels. */
  private static Map<String, Object> chain(Map<String, Object> inherited, Map<String, Object> own) {
    if (inherited.isEmpty()) {
      return truthy(own);
    }
    Map<String, Object> merged = new LinkedHashMap<>(inherited);
    merged.putAll(own);
    return truthy(merged);
  }

  /** Drops falsy isolation labels (upstream's {@code if (!label) return}). */
  private static Map<String, Object> truthy(Map<String, Object> table) {
    Map<String, Object> kept = null;
    for (Map.Entry<String, Object> row : table.entrySet()) {
      Object value = row.getValue();
      if (value == null
          || Boolean.FALSE.equals(value)
          || (value instanceof String label && label.isEmpty())) {
        if (kept == null) {
          kept = new LinkedHashMap<>(table);
        }
        kept.remove(row.getKey());
      }
    }
    return kept == null ? table : java.util.Collections.unmodifiableMap(kept);
  }

  private static ComponentSpec wrapIsolation(
      Map<String, Object> isolate,
      String entryId,
      ComponentSpec inner,
      ComponentResolver resolver) {
    ComponentSpec current = inner;
    List<Map.Entry<String, Object>> overrides = new ArrayList<>(isolate.entrySet());
    java.util.Collections.reverse(overrides); // the first table service wraps outermost
    for (Map.Entry<String, Object> override : overrides) {
      Class<?> type = resolver.serviceType(override.getKey());
      Object value = override.getValue();
      if (!(value instanceof Boolean) && !(value instanceof String)) {
        throw new CordisException(
            "isolate label must be true, false, or a string: " + override.getKey() + " = " + value);
      }
      String realm =
          Boolean.TRUE.equals(value)
              ? "#" + entryId // local: unique to this entry (upstream's LocalRealm)
              : "@" + value; // shared by label (upstream's GlobalRealm)
      current = new ComponentSpec.Isolate(type, realm, List.of(current));
    }
    return current;
  }

  private static void requireId(CordisEntry entry) {
    if (entry.id() == null || entry.id().isBlank()) {
      throw new CordisException(
          "an entry without an id cannot be mounted stably (name: " + entry.name() + ")");
    }
  }

  private static List<CordisEntry> children(CordisEntry group) {
    if (group.config() instanceof List<?> list) {
      @SuppressWarnings("unchecked")
      List<CordisEntry> children = (List<CordisEntry>) list;
      return children;
    }
    throw new CordisException("a group entry's config must be a list of entries: " + group.id());
  }
}
