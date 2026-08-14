/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A declarative component configuration (paper, Section 5.2.1): an ordered, immutable set of {@link
 * ComponentEntry}s keyed by id, the unit of {@code Loader.reconcile} diffing.
 *
 * @param entries the component entries, ids unique, never null
 */
public record LoaderConfig(List<ComponentEntry> entries) {

  /** Validates the entries and the uniqueness of their ids. */
  public LoaderConfig {
    Objects.requireNonNull(entries, "entries");
    entries = List.copyOf(entries);
    Set<String> ids = new HashSet<>();
    for (ComponentEntry entry : entries) {
      if (!ids.add(entry.id())) {
        throw new IllegalArgumentException("Duplicate component entry id: " + entry.id());
      }
    }
  }

  /**
   * Returns a configuration of the given entries.
   *
   * @param entries the component entries
   * @return the configuration
   * @throws NullPointerException if {@code entries} or any element is null
   * @throws IllegalArgumentException if two entries share an id
   */
  public static LoaderConfig of(ComponentEntry... entries) {
    Objects.requireNonNull(entries, "entries");
    return new LoaderConfig(Arrays.asList(entries));
  }
}
