/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One node of a loader composition tree (decision D26, the JVM form of upstream's
 * entry/group/isolate/tree configuration): {@link Loader#reconcileTree} flattens a tree of these
 * specs into per-entry load contexts and reconciles the flattened set through the D18 engine.
 */
public sealed interface ComponentSpec {

  /**
   * A single component: the plain {@link ComponentEntry} of the flattened form.
   *
   * @param id the entry id (a flattened id may carry group prefixes, ':'-separated)
   * @param component the plugin to apply
   */
  record Entry(String id, Plugin component) implements ComponentSpec {

    /** Validates the components of this entry, rejecting an empty id like ComponentEntry. */
    public Entry {
      Objects.requireNonNull(id, "id");
      if (id.isEmpty()) {
        throw new IllegalArgumentException("entry id must not be empty");
      }
      Objects.requireNonNull(component, "component");
    }
  }

  /**
   * A group: its children's ids get the group's id as a {@code ':'}-separated prefix, and the group
   * itself loads nothing (upstream's EntryGroup in declarative form).
   *
   * @param id the group id
   * @param children the grouped specs, never null
   */
  record Group(String id, List<ComponentSpec> children) implements ComponentSpec {

    /** Validates the components of this group, rejecting an empty id like Entry. */
    public Group {
      Objects.requireNonNull(id, "id");
      if (id.isEmpty()) {
        throw new IllegalArgumentException("group id must not be empty");
      }
      Objects.requireNonNull(children, "children");
      children = List.copyOf(children);
    }
  }

  /**
   * An isolation realm: its children load into a derived {@link Context#isolate(Class, String)}
   * context, so bindings of the same key in sibling realms coexist.
   *
   * @param type the isolated service type
   * @param realm the realm label of the derived subtree
   * @param children the isolated specs, never null
   */
  record Isolate(Class<?> type, String realm, List<ComponentSpec> children)
      implements ComponentSpec {

    /** Validates the components of this isolation. */
    public Isolate {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(realm, "realm");
      Objects.requireNonNull(children, "children");
      children = List.copyOf(children);
    }
  }

  /**
   * A reference to another configuration source, resolved against the reconcile's base directory:
   * the resolver reads the referenced file and returns the specs to inline (upstream's include
   * directive in typed form - no file format is imposed, so the resolver can parse YAML, JSON, or
   * properties).
   *
   * @param file the referenced file, resolved relative to the base directory
   * @param resolver reads {@code file} (an absolute path) and returns the specs to inline
   */
  record Include(Path file, Function<Path, List<ComponentSpec>> resolver) implements ComponentSpec {

    /** Validates the components of this include. */
    public Include {
      Objects.requireNonNull(file, "file");
      Objects.requireNonNull(resolver, "resolver");
    }
  }
}
