/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.util.Objects;

/**
 * One entry of a {@link LoaderConfig} (paper, Section 5.2.1): a component identified by a stable
 * {@code id}, keyed for configuration diffing.
 *
 * <p>Identity of the component instance is the version: reconciling a config whose entry for an id
 * carries a different (non-equal) component instance replaces - reloads - that component, while an
 * equal instance keeps the running one untouched.
 *
 * @param id the stable entry identifier, never null or empty
 * @param component the component instance, never null
 */
public record ComponentEntry(String id, Plugin component) {

  /** Validates the components of this entry. */
  public ComponentEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(component, "component");
    if (id.isEmpty()) {
      throw new IllegalArgumentException("Component entry id must not be empty");
    }
  }

  /**
   * Returns an entry.
   *
   * @param id the stable entry identifier
   * @param component the component instance
   * @return the entry
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code id} is empty
   */
  public static ComponentEntry of(String id, Plugin component) {
    return new ComponentEntry(id, component);
  }
}
